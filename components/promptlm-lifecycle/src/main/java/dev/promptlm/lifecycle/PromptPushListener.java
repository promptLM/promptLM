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

package dev.promptlm.lifecycle;

import dev.promptlm.domain.events.PromptExecutedEvent;
import dev.promptlm.domain.events.PromptPushedEvent;
import dev.promptlm.domain.promptspec.PromptSpec;
import dev.promptlm.lifecycle.application.PromptStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Listens for {@link PromptExecutedEvent} and performs the single remote push for the
 * deferred-push save flow (issue #352).
 *
 * <p>The save flow committed locally only; this listener amends that local commit with the
 * now-attached LLM response and pushes once. On failure, no push has happened — the local
 * commit is preserved so a Retry can re-execute against the same revision and amend again.
 */
@Component
class PromptPushListener {

    private static final Logger log = LoggerFactory.getLogger(PromptPushListener.class);

    private final PromptStorePort repository;
    private final ApplicationEventPublisher eventPublisher;

    PromptPushListener(PromptStorePort repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @ApplicationModuleListener
    void onPromptExecuted(PromptExecutedEvent event) {
        PromptSpec executed = event.promptSpec();
        if (executed == null) {
            return;
        }
        try {
            PromptSpec pushed = repository.amendAndPushHead(executed);
            eventPublisher.publishEvent(new PromptPushedEvent(pushed));
        } catch (RuntimeException ex) {
            // The push failure leaves the local commit intact — the user can retry from the
            // UI. Swallowing the exception keeps this async listener from poisoning the
            // event-publication transaction.
            log.warn("Failed to amend+push prompt {} after execution; local commit preserved",
                    executed.getId(), ex);
        }
    }
}
