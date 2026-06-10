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

package dev.promptlm.domain.events;

import dev.promptlm.domain.promptspec.PromptSpec;

/**
 * Domain event fired when running a {@link PromptSpec} against the LLM failed.
 *
 * <p>Issue #352: under the deferred-push design, no remote push occurs in this branch —
 * the local draft commit is preserved and the UI can offer Retry. {@link #reason()}
 * carries a short human-readable message; {@link #errorClass()} carries the simple class
 * name of the underlying exception for UI categorisation.
 */
public record PromptExecutionFailedEvent(PromptSpec promptSpec, String reason, String errorClass) {

    public static PromptExecutionFailedEvent from(PromptSpec promptSpec, Throwable cause) {
        if (cause == null) {
            return new PromptExecutionFailedEvent(promptSpec, "Unknown execution failure", null);
        }
        String reason = cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage()
                : cause.getClass().getSimpleName();
        return new PromptExecutionFailedEvent(promptSpec, reason, cause.getClass().getSimpleName());
    }
}
