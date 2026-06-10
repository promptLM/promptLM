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
import dev.promptlm.domain.promptspec.PromptSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Listens for save (create/update) events and runs the prompt through the LLM. Emits
 * {@link PromptExecutedEvent} on success, {@link PromptExecutionFailedEvent} on failure.
 *
 * <p>Issue #352: under the deferred-push design, save commits locally only. This listener
 * runs the LLM asynchronously; the downstream {@code PromptPushListener} amends the local
 * commit with the response and pushes once.
 */
@Component
class PromptExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(PromptExecutionListener.class);

    private final PromptExecutor promptExecutor;
    private final ApplicationEventPublisher eventPublisher;

    PromptExecutionListener(PromptExecutor promptExecutor, ApplicationEventPublisher eventPublisher) {
        this.promptExecutor = promptExecutor;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    @Async
    void onPromptCreated(PromptCreatedEvent event) {
        runAndPublish(event.promptSpec());
    }

    @EventListener
    @Async
    void onPromptUpdated(PromptUpdatedEvent event) {
        runAndPublish(event.promptSpec());
    }

    private void runAndPublish(PromptSpec spec) {
        try {
            PromptSpec executed = promptExecutor.runPromptAndAttachResponse(spec);
            eventPublisher.publishEvent(new PromptExecutedEvent(executed));
        } catch (RuntimeException ex) {
            // #352: do NOT rethrow. The save commit is local-only; rethrowing here would
            // surface as an async listener failure and break the "save shows a fast 202,
            // execution runs in the background" UX. Emit the failure event so the SSE
            // listener can render an error + Retry control.
            log.warn("Prompt execution failed for {} ({}); emitting PromptExecutionFailedEvent",
                    spec.getId(), ex.getClass().getSimpleName());
            eventPublisher.publishEvent(PromptExecutionFailedEvent.from(spec, ex));
        }
    }
}
