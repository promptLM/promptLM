/*
 * Copyright 2025 promptLM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.promptlm.web;

import dev.promptlm.domain.events.PromptPushFailedEvent;
import dev.promptlm.domain.events.PromptPushedEvent;
import dev.promptlm.domain.promptspec.ChatCompletionRequest;
import dev.promptlm.domain.promptspec.PromptSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PromptExecutionSseListenerTest {

    @Mock
    private SseStatusPublisher publisher;

    @Test
    void onPromptPushedEmitsPushedStatus() {
        PromptExecutionSseListener listener = new PromptExecutionSseListener(publisher);
        PromptSpec spec = baseSpec();

        listener.onPromptPushed(new PromptPushedEvent(spec));

        verify(publisher).sendStatus(
                eq(PromptExecutionSseListener.channelKey(spec.getId())),
                eq(PromptExecutionSseListener.OPERATION),
                eq(PromptExecutionSseListener.STATUS_PUSHED),
                eq("Pushed to GitHub"),
                any());
    }

    @Test
    void onPromptPushFailedEmitsPushFailedStatusWithReasonAndErrorClass() {
        PromptExecutionSseListener listener = new PromptExecutionSseListener(publisher);
        PromptSpec spec = baseSpec();
        PromptPushFailedEvent event = PromptPushFailedEvent.from(
                spec, new IllegalStateException("remote rejected non-fast-forward"));

        listener.onPromptPushFailed(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailsCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(publisher).sendStatus(
                eq(PromptExecutionSseListener.channelKey(spec.getId())),
                eq(PromptExecutionSseListener.OPERATION),
                eq(PromptExecutionSseListener.STATUS_PUSH_FAILED),
                eq("Push to GitHub failed"),
                detailsCaptor.capture());

        Map<String, Object> details = detailsCaptor.getValue();
        assertThat(details).containsEntry("promptId", spec.getId());
        assertThat(details).containsEntry("reason", "remote rejected non-fast-forward");
        assertThat(details).containsEntry("errorClass", "IllegalStateException");
    }

    @Test
    void onPromptPushFailedWithBlankPromptIdEmitsNothing() {
        PromptExecutionSseListener listener = new PromptExecutionSseListener(publisher);
        PromptSpec specWithoutId = PromptSpec.builder()
                .withGroup("group")
                .withName("name")
                .withVersion("1.0.0-SNAPSHOT")
                .withRevision(1)
                .withDescription("desc")
                .withRequest(ChatCompletionRequest.builder()
                        .withVendor("openai")
                        .withModel("gpt-4")
                        .withMessages(List.of())
                        .build())
                .build();

        listener.onPromptPushFailed(
                PromptPushFailedEvent.from(specWithoutId, new RuntimeException("nope")));

        verifyNoInteractions(publisher);
    }

    @Test
    void onPromptPushFailedConstantMatchesAsyncApiEnum() {
        // The contract value `push-failed` is shared with the AsyncAPI store-status enum
        // and the frontend `isTerminalPromptExecutionStatusEvent` helper. Pin it here so
        // a careless rename in the listener can't desync the wire format.
        assertThat(PromptExecutionSseListener.STATUS_PUSH_FAILED).isEqualTo("push-failed");
    }

    private static PromptSpec baseSpec() {
        return PromptSpec.builder()
                .withGroup("group")
                .withName("name")
                .withVersion("1.0.0-SNAPSHOT")
                .withRevision(1)
                .withDescription("desc")
                .withRequest(ChatCompletionRequest.builder()
                        .withVendor("openai")
                        .withModel("gpt-4")
                        .withMessages(List.of())
                        .build())
                .build()
                .withId("group/name");
    }
}
