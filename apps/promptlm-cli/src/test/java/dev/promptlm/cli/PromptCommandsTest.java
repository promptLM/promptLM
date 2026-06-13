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

package dev.promptlm.cli;

import dev.promptlm.domain.promptspec.ChatCompletionRequest;
import dev.promptlm.domain.promptspec.PromptSpec;
import dev.promptlm.domain.promptspec.ReleaseMetadata;
import dev.promptlm.domain.promptspec.Request;
import dev.promptlm.domain.projectspec.ProjectSpec;
import dev.promptlm.lifecycle.PromptLifecycleFacade;
import dev.promptlm.infrastructure.config.SerializingAppContext;
import dev.promptlm.store.api.PromptStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.shell.core.command.availability.Availability;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptCommandsTest {

    @Test
    void createUsesBackendTemplateAsDraftSource() {
        PromptLifecycleFacade lifecycleFacade = mock(PromptLifecycleFacade.class);

        PromptSpec.Placeholders placeholders = new PromptSpec.Placeholders();
        placeholders.setStartPattern("{{");
        placeholders.setEndPattern("}}");
        placeholders.setList(List.of(new PromptSpec.Placeholder("customer_name", "Taylor")));

        PromptSpec template = PromptSpec.builder()
                .withGroup("support")
                .withName("support-prompt")
                .withVersion("1.0.0-SNAPSHOT")
                .withRevision(1)
                .withDescription("Assist support agents")
                .withRequest(ChatCompletionRequest.builder()
                        .withType(ChatCompletionRequest.TYPE)
                        .withVendor("openai")
                        .withModel("gpt-4o")
                        .withMessages(List.of(
                                ChatCompletionRequest.Message.builder()
                                        .withRole("system")
                                        .withContent("You are a helpful assistant.")
                                        .build(),
                                ChatCompletionRequest.Message.builder()
                                        .withRole("user")
                                        .withContent("Help the customer.")
                                        .build()
                        ))
                        .build())
                .withPlaceholders(placeholders)
                .build();
        when(lifecycleFacade.createDefaultPromptSpec()).thenReturn(template);

        PromptSpec createdPrompt = template.withId("support/new-prompt");
        when(lifecycleFacade.createPromptSpec(any(PromptSpec.class))).thenReturn(createdPrompt);

        PromptCommands commands = new PromptCommands(
                mock(PromptRenderer.class),
                mock(PromptStore.class),
                lifecycleFacade
        );

        String id = commands.create(
                "new-prompt",
                "support",
                "Help account owner.",
                List.of("customer_name=Avery")
        );

        assertEquals("support/new-prompt", id);

        ArgumentCaptor<PromptSpec> createdCaptor = ArgumentCaptor.forClass(PromptSpec.class);
        verify(lifecycleFacade).createPromptSpec(createdCaptor.capture());
        PromptSpec created = createdCaptor.getValue();
        assertEquals("support", created.getGroup());
        assertEquals("new-prompt", created.getName());
        ChatCompletionRequest request = (ChatCompletionRequest) created.getRequest();
        assertEquals("openai", request.getVendor());
        assertEquals("gpt-4o", request.getModel());
        assertEquals("You are a helpful assistant.", request.getMessages().getFirst().getContent());
        assertEquals("Help account owner.", request.getMessages().getLast().getContent());
        assertEquals("Avery", created.getPlaceholders().getDefaults().get("customer_name"));
    }

    @Test
    void createFailsWhenBackendTemplateRequestIsNotChatCompletion() {
        PromptLifecycleFacade lifecycleFacade = mock(PromptLifecycleFacade.class);

        Request nonChatRequest = mock(Request.class);
        PromptSpec template = PromptSpec.builder()
                .withGroup("support")
                .withName("support-prompt")
                .withVersion("1.0.0-SNAPSHOT")
                .withRevision(1)
                .withDescription("Assist support agents")
                .withRequest(nonChatRequest)
                .build();
        when(lifecycleFacade.createDefaultPromptSpec()).thenReturn(template);

        PromptCommands commands = new PromptCommands(
                mock(PromptRenderer.class),
                mock(PromptStore.class),
                lifecycleFacade
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> commands.create(
                "new-prompt",
                "support",
                "Help account owner.",
                List.of("customer_name=Avery")
        ));
        assertTrue(ex.getMessage().contains("chat/completion"));
    }

    @Test
    void availabilityIsUnavailableWhenNoActiveProjectIsSelected() {
        SerializingAppContext context = mock(SerializingAppContext.class);
        when(context.getActiveProject()).thenReturn(null);

        Availability availability = new PromptCommands.PromptAvailabilityProvider(context).get();

        assertFalse(availability.isAvailable());
        assertTrue(availability.reason().contains("not connected to any store"));
    }

    @Test
    void completeReleaseAvailabilityIsUnavailableOutsidePrTwoPhaseMode() {
        SerializingAppContext context = mock(SerializingAppContext.class);
        ProjectSpec activeProject = new ProjectSpec();
        activeProject.setRepoDir(Path.of("/tmp/repo"));
        when(context.getActiveProject()).thenReturn(activeProject);

        Availability availability = new PromptCommands.CompleteReleaseAvailabilityProvider(
                context,
                ReleaseMetadata.MODE_DIRECT
        ).get();

        assertFalse(availability.isAvailable());
        assertTrue(availability.reason().contains("promptlm.release.promotion.mode=pr_two_phase"));
    }

    @Test
    void completeReleaseAvailabilityIsAvailableInPrTwoPhaseMode() {
        SerializingAppContext context = mock(SerializingAppContext.class);
        ProjectSpec activeProject = new ProjectSpec();
        activeProject.setRepoDir(Path.of("/tmp/repo"));
        when(context.getActiveProject()).thenReturn(activeProject);

        Availability availability = new PromptCommands.CompleteReleaseAvailabilityProvider(
                context,
                ReleaseMetadata.MODE_PR_TWO_PHASE
        ).get();

        assertTrue(availability.isAvailable());
    }

    @Test
    void releaseRendersRequestedStateWhenPrTwoPhaseIsPending() {
        PromptLifecycleFacade lifecycleFacade = mock(PromptLifecycleFacade.class);
        PromptSpec requested = PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion("1.0.0")
                .withRevision(0)
                .withDescription("desc")
                .withRequest(ChatCompletionRequest.builder().withType(ChatCompletionRequest.TYPE).build())
                .build()
                .withReleaseMetadata(new ReleaseMetadata(
                        ReleaseMetadata.STATE_REQUESTED,
                        ReleaseMetadata.MODE_PR_TWO_PHASE,
                        "1.0.0",
                        "support/welcome-v1.0.0",
                        "release/support-welcome-1.0.0",
                        11,
                        "https://github.com/promptLM/promptlm-app/pull/11",
                        false
                ));
        when(lifecycleFacade.release("prompt-id")).thenReturn(requested);

        PromptCommands commands = new PromptCommands(mock(PromptRenderer.class), mock(PromptStore.class), lifecycleFacade);
        String result = commands.release("prompt-id");

        assertEquals("requested 1.0.0 pr#11", result);
    }

    @Test
    void completeReleaseDelegatesToLifecycleFacade() {
        PromptLifecycleFacade lifecycleFacade = mock(PromptLifecycleFacade.class);
        PromptSpec released = PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion("1.0.0")
                .withRevision(0)
                .withDescription("desc")
                .withRequest(ChatCompletionRequest.builder().withType(ChatCompletionRequest.TYPE).build())
                .build()
                .withReleaseMetadata(new ReleaseMetadata(
                        ReleaseMetadata.STATE_RELEASED,
                        ReleaseMetadata.MODE_PR_TWO_PHASE,
                        "1.0.0",
                        "support/welcome-v1.0.0",
                        "main",
                        11,
                        "https://github.com/promptLM/promptlm-app/pull/11",
                        false
                ));
        when(lifecycleFacade.completeRelease("prompt-id", "11")).thenReturn(released);

        PromptCommands commands = new PromptCommands(mock(PromptRenderer.class), mock(PromptStore.class), lifecycleFacade);
        String result = commands.completeRelease("prompt-id", "11");

        assertEquals("1.0.0", result);
        verify(lifecycleFacade).completeRelease("prompt-id", "11");
    }
}
