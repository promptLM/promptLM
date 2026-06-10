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

import dev.promptlm.domain.events.PromptCreatedEvent;
import dev.promptlm.domain.events.PromptExecutedEvent;
import dev.promptlm.domain.events.PromptExecutionFailedEvent;
import dev.promptlm.domain.events.PromptPushedEvent;
import dev.promptlm.domain.events.PromptUpdatedEvent;
import dev.promptlm.domain.promptspec.PromptSpec;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges domain execution events to SSE clients listening on the
 * {@code prompt:{id}:execution} channel (issue #352).
 *
 * <p>Wire-format note: the SSE payload reuses the existing {@link StoreStatusEvent}
 * envelope so the editor can subscribe with the same generated client. The
 * {@code operation} field is set to {@code "prompt-execution"} and the {@code status}
 * field carries one of {@code executing | executed | failed | pushed} (issue #352 spec).
 * Each event also includes {@code details.promptId}.
 */
@Component
public class PromptExecutionSseListener {

    public static final String OPERATION = "prompt-execution";
    public static final String STATUS_EXECUTING = "executing";
    public static final String STATUS_EXECUTED = "executed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_PUSHED = "pushed";

    private final SseStatusPublisher publisher;

    public PromptExecutionSseListener(SseStatusPublisher publisher) {
        this.publisher = publisher;
    }

    public static String channelKey(String promptId) {
        return "prompt:" + promptId + ":execution";
    }

    @EventListener
    void onPromptCreated(PromptCreatedEvent event) {
        emit(event.promptSpec(), STATUS_EXECUTING, "Executing prompt");
    }

    @EventListener
    void onPromptUpdated(PromptUpdatedEvent event) {
        emit(event.promptSpec(), STATUS_EXECUTING, "Executing prompt");
    }

    @EventListener
    void onPromptExecuted(PromptExecutedEvent event) {
        emit(event.promptSpec(), STATUS_EXECUTED, "Prompt executed");
    }

    @EventListener
    void onPromptExecutionFailed(PromptExecutionFailedEvent event) {
        Map<String, Object> details = baseDetails(event.promptSpec());
        if (StringUtils.hasText(event.reason())) {
            details.put("reason", event.reason());
        }
        if (StringUtils.hasText(event.errorClass())) {
            details.put("errorClass", event.errorClass());
        }
        publish(event.promptSpec(), STATUS_FAILED, "Prompt execution failed", details);
    }

    @EventListener
    void onPromptPushed(PromptPushedEvent event) {
        emit(event.promptSpec(), STATUS_PUSHED, "Pushed to GitHub");
    }

    private void emit(PromptSpec spec, String status, String message) {
        publish(spec, status, message, baseDetails(spec));
    }

    private void publish(PromptSpec spec, String status, String message, Map<String, Object> details) {
        if (spec == null || spec.getId() == null || spec.getId().isBlank()) {
            return;
        }
        publisher.sendStatus(channelKey(spec.getId()), OPERATION, status, message, details);
    }

    private static Map<String, Object> baseDetails(PromptSpec spec) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (spec != null && spec.getId() != null) {
            details.put("promptId", spec.getId());
        }
        return details;
    }
}
