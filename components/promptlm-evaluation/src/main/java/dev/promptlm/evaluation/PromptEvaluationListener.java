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

package dev.promptlm.evaluation;

import dev.promptlm.domain.events.PromptEvaluatedEvent;
import dev.promptlm.domain.events.PromptExecutedEvent;
import dev.promptlm.domain.promptspec.PromptSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
class PromptEvaluationListener {
    private static final Logger log = LoggerFactory.getLogger(PromptEvaluationListener.class);

    private final PromptEvaluator evaluator;
    private final ApplicationEventPublisher eventPublisher;

    PromptEvaluationListener(PromptEvaluator evaluator, ApplicationEventPublisher eventPublisher) {
        this.evaluator = evaluator;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    @Async
    void onPromptExecuted(PromptExecutedEvent event) {
        PromptSpec promptSpec = event.promptSpec();
        String promptId = promptSpec == null || promptSpec.getId() == null ? "<none>" : promptSpec.getId();
        long startedAt = System.nanoTime();

        log.info("PromptExecutedEvent received: promptId={}", promptId);
        try {
            PromptSpec evaluated = evaluator.evaluateAndAttachResults(promptSpec);
            eventPublisher.publishEvent(new PromptEvaluatedEvent(evaluated));

            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            var results = EvaluationExtensionSupport.extractResults(evaluated);
            log.info("PromptEvaluatedEvent published: promptId={}, status={}, durationMs={}",
                    promptId,
                    results == null ? "<none>" : results.getStatus(),
                    durationMs);
        } catch (RuntimeException exception) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.error("Prompt evaluation listener failed: promptId={}, durationMs={}, errorType={}, message={}",
                    promptId, durationMs, exception.getClass().getName(), exception.getMessage());
            throw exception;
        }
    }
}
