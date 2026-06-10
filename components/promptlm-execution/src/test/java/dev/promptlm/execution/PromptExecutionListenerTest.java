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

package dev.promptlm.execution;

import dev.promptlm.domain.events.PromptCreatedEvent;
import dev.promptlm.domain.events.PromptExecutedEvent;
import dev.promptlm.domain.events.PromptExecutionFailedEvent;
import dev.promptlm.domain.events.PromptUpdatedEvent;
import dev.promptlm.domain.promptspec.ChatCompletionRequest;
import dev.promptlm.domain.promptspec.PromptSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptExecutionListenerTest {

    @Mock
    private PromptExecutor promptExecutor;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void publishesPromptExecutedEventWithExecutedPromptSpec() {
        PromptSpec created = basePromptSpec();
        PromptSpec executed = created.withDescription("executed");

        when(promptExecutor.runPromptAndAttachResponse(created)).thenReturn(executed);

        PromptExecutionListener listener = new PromptExecutionListener(promptExecutor, eventPublisher);
        listener.onPromptCreated(new PromptCreatedEvent(created));

        verify(promptExecutor).runPromptAndAttachResponse(created);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(PromptExecutedEvent.class);

        PromptExecutedEvent event = (PromptExecutedEvent) eventCaptor.getValue();
        assertThat(event.promptSpec()).isEqualTo(executed);
    }

    @Test
    void publishesPromptExecutedEventForUpdates() {
        PromptSpec updated = basePromptSpec();
        PromptSpec executed = updated.withDescription("executed");

        when(promptExecutor.runPromptAndAttachResponse(updated)).thenReturn(executed);

        PromptExecutionListener listener = new PromptExecutionListener(promptExecutor, eventPublisher);
        listener.onPromptUpdated(new PromptUpdatedEvent(updated));

        verify(promptExecutor).runPromptAndAttachResponse(updated);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(PromptExecutedEvent.class);
    }

    @Test
    void publishesPromptExecutionFailedEventWhenExecutionFailsInsteadOfRethrowing() {
        // #352: under the deferred-push design the LLM run is async. A failure here must
        // not be rethrown — that would mark the @ApplicationModuleListener delivery as
        // failed and surface noise. The push listener also must not run, so emit the
        // failure event instead.
        PromptSpec created = basePromptSpec();
        RuntimeException failure = new RuntimeException("vendor 500");

        when(promptExecutor.runPromptAndAttachResponse(created)).thenThrow(failure);

        PromptExecutionListener listener = new PromptExecutionListener(promptExecutor, eventPublisher);
        listener.onPromptCreated(new PromptCreatedEvent(created));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(PromptExecutionFailedEvent.class);
        PromptExecutionFailedEvent failed = (PromptExecutionFailedEvent) eventCaptor.getValue();
        assertThat(failed.promptSpec()).isEqualTo(created);
        assertThat(failed.reason()).isEqualTo("vendor 500");
        assertThat(failed.errorClass()).isEqualTo("RuntimeException");
    }

    private static PromptSpec basePromptSpec() {
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
                .build();
    }
}
