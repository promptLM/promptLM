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


import dev.promptlm.lifecycle.PromptLifecycleFacade;
import dev.promptlm.domain.promptspec.ChatCompletionRequest;
import dev.promptlm.domain.promptspec.PromptSpec;
import dev.promptlm.domain.promptspec.ReleaseMetadata;
import dev.promptlm.infrastructure.config.SerializingAppContext;
import dev.promptlm.store.api.PromptStore;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.shell.core.command.availability.Availability;
import org.springframework.shell.core.command.availability.AvailabilityProvider;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Spring Shell commands for managing prompt lifecycle operations.
 * Provides {@code prompt create}, {@code prompt change}, {@code prompt release},
 * and {@code prompt show} commands.
 */
@Component
public class PromptCommands {

    private final PromptRenderer promptRenderer;
    private final PromptStore promptStore;
    private final PromptLifecycleFacade promptLifecycleFacade;

    public PromptCommands(PromptRenderer promptRendererProvider,
                          PromptStore promptStoreProvider,
                          PromptLifecycleFacade promptLifecycleFacadeProvider) {
        this.promptRenderer = promptRendererProvider;
        this.promptStore = promptStoreProvider;
        this.promptLifecycleFacade = promptLifecycleFacadeProvider;
    }

    @Command(
            name = "prompt create",
            description = "Create a prompt draft from the backend template.",
            availabilityProvider = "promptAvailabilityProvider"
    )
    public String create(
            @Option(longName = "name", required = true, description = "Prompt name.") String name,
            @Option(longName = "group", defaultValue = "default", description = "Prompt group.") String group,
            @Option(longName = "userMessage", shortName = 'u', required = true, description = "User message template.") String userMessage,
            @Option(longName = "placeholder", defaultValue = "{}", description = "Placeholder override in key=value format. Repeat to pass multiple placeholders.") List<String> placeholder) {
        PromptSpec template = promptLifecycleFacade.createDefaultPromptSpec();
        if (!(template.getRequest() instanceof ChatCompletionRequest templateRequest)) {
            throw new IllegalStateException("Prompt template request must be type chat/completion.");
        }

        List<ChatCompletionRequest.Message> messages = new ArrayList<>();
        String templateSystemMessage = readSystemMessage(templateRequest);
        if (!templateSystemMessage.isBlank()) {
            messages.add(ChatCompletionRequest.Message.builder()
                    .withRole("system")
                    .withContent(templateSystemMessage)
                    .build());
        }
        messages.add(ChatCompletionRequest.Message.builder()
                .withRole("user")
                .withContent(userMessage)
                .build());


        Map<String, String> placeholderMap = parsePlaceholderListToMap(placeholder);

        PromptSpec.Placeholders placeholders = mergePlaceholders(template.getPlaceholders(), placeholderMap);
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .withType(templateRequest.getType())
                .withVendor(templateRequest.getVendor())
                .withModel(templateRequest.getModel())
                .withUrl(templateRequest.getUrl())
                .withModelSnapshot(templateRequest.getModelSnapshot())
                .withParameters(templateRequest.getParameters())
                .withMessages(messages)
                .build();

        PromptSpec.Builder promptSpecBuilder = PromptSpec.builderFrom(template);
        promptSpecBuilder.withId(null);
        promptSpecBuilder.withGroup(group);
        promptSpecBuilder.withName(name);
        promptSpecBuilder.withRequest(request);
        promptSpecBuilder.withPlaceholders(placeholders);

        PromptSpec promptSpec = promptSpecBuilder.build();
        return promptLifecycleFacade.createPromptSpec(promptSpec).getId();
    }

    private static @NonNull Map<String, String> parsePlaceholderListToMap(List<String> placeholder) {
        Map<String, String> placeholderMap = placeholder.stream()
                .map(v -> v.split("="))
                .collect(Collectors.toMap(k -> k[0], v -> v[1]));
        return placeholderMap;
    }

    @Command(
            name = "prompt show",
            description = "Show the latest version of a prompt.",
            availabilityProvider = "promptAvailabilityProvider"
    )
    public String show(
            @Option(longName = "id", required = true, description = "Prompt id.") String id
    ) {
        Optional<PromptSpec> prompt = promptStore.getLatestVersion(id);
        if (prompt.isPresent()) {
            return promptRenderer.render(prompt.get());
        } else {
            return "No prompt found for id " + id;
        }
    }

    @Command(
            name = "prompt release",
            description = "Request or perform a prompt release.",
            availabilityProvider = "promptAvailabilityProvider"
    )
    public String release(
            @Option(longName = "id", required = true, description = "Prompt id.") String id
    ) {
        PromptSpec promptSpec = promptLifecycleFacade.release(id);
        return renderReleaseResult(promptSpec);
    }

    @Command(
            name = "prompt release complete",
            description = "Complete a two-phase prompt release after the release PR is merged.",
            availabilityProvider = "promptReleaseCompleteAvailabilityProvider"
    )
    public String completeRelease(
            @Option(longName = "id", required = true, description = "Prompt id.") String id,
            @Option(longName = "pr", required = true, description = "Pull request number or reference.") String pullRequestReference
    ) {
        PromptSpec promptSpec = promptLifecycleFacade.completeRelease(id, pullRequestReference);
        return renderReleaseResult(promptSpec);
    }

    private static String readSystemMessage(ChatCompletionRequest request) {
        List<ChatCompletionRequest.Message> messages = request.getMessages() != null
                ? request.getMessages()
                : List.of();
        return messages.stream()
                .filter(message -> "system".equalsIgnoreCase(message.getRole()))
                .map(ChatCompletionRequest.Message::getContent)
                .findFirst()
                .orElse("");
    }

    private static PromptSpec.Placeholders mergePlaceholders(
            PromptSpec.Placeholders templatePlaceholders,
            Map<String, String> overrides
    ) {
        if (templatePlaceholders == null) {
            throw new IllegalStateException("Prompt template placeholders must be provided by the backend.");
        }
        PromptSpec.Placeholders placeholders = new PromptSpec.Placeholders();
        placeholders.setStartPattern(templatePlaceholders.getStartPattern());
        placeholders.setEndPattern(templatePlaceholders.getEndPattern());

        Map<String, String> mergedDefaults = new LinkedHashMap<>();
        mergedDefaults.putAll(templatePlaceholders.getDefaults());
        mergedDefaults.putAll(overrides);

        placeholders.setList(mergedDefaults.entrySet().stream()
                .map(entry -> new PromptSpec.Placeholder(entry.getKey(), entry.getValue()))
                .toList());
        return placeholders;
    }

    private static String renderReleaseResult(PromptSpec promptSpec) {
        ReleaseMetadata releaseMetadata = promptSpec.getReleaseMetadata();
        if (releaseMetadata == null) {
            throw new IllegalStateException("Release response is missing required release metadata");
        }

        if (releaseMetadata.isRequested()) {
            StringBuilder builder = new StringBuilder("requested");
            if (releaseMetadata.version() != null) {
                builder.append(" ").append(releaseMetadata.version());
            }
            if (releaseMetadata.prNumber() != null) {
                builder.append(" pr#").append(releaseMetadata.prNumber());
            }
            return builder.toString();
        }

        return releaseMetadata.version() != null ? releaseMetadata.version() : promptSpec.getVersion();
    }

    @Component("promptAvailabilityProvider")
    static class PromptAvailabilityProvider implements AvailabilityProvider {

        private final SerializingAppContext context;

        PromptAvailabilityProvider(SerializingAppContext context) {
            this.context = context;
        }

        @Override
        public Availability get() {
            return resolveContextAvailability(context);
        }

        static Availability resolveContextAvailability(SerializingAppContext context) {
            return context != null
                    && context.getActiveProject() != null
                    && context.getActiveProject().getRepoDir() != null
                    ? Availability.available()
                    : Availability.unavailable("the CLI is not connected to any store. Create a new or select an existing store");
        }
    }

    @Component("promptReleaseCompleteAvailabilityProvider")
    static class CompleteReleaseAvailabilityProvider implements AvailabilityProvider {

        private static final String PR_TWO_PHASE_MODE = "pr_two_phase";

        private final SerializingAppContext context;
        private final String releasePromotionMode;

        CompleteReleaseAvailabilityProvider(
                SerializingAppContext context,
                @Value("${promptlm.release.promotion.mode:direct}") String releasePromotionMode
        ) {
            this.context = context;
            this.releasePromotionMode = releasePromotionMode;
        }

        @Override
        public Availability get() {
            Availability contextAvailability = PromptAvailabilityProvider.resolveContextAvailability(context);
            if (!contextAvailability.isAvailable()) {
                return contextAvailability;
            }

            return isPrTwoPhaseModeEnabled()
                    ? Availability.available()
                    : Availability.unavailable(
                    "prompt release complete is only available when promptlm.release.promotion.mode=pr_two_phase");
        }

        private boolean isPrTwoPhaseModeEnabled() {
            return PR_TWO_PHASE_MODE.equalsIgnoreCase(releasePromotionMode == null ? "" : releasePromotionMode.trim());
        }
    }

}
