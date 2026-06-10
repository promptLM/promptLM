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
 * Domain event fired when an existing {@link PromptSpec} has been updated (committed locally,
 * not yet pushed to the remote). Mirrors {@link PromptCreatedEvent} for the update path so
 * downstream listeners (e.g. execution + push) can subscribe symmetrically.
 *
 * <p>Issue #352: under the deferred-push design, save (both create and update) commits
 * locally first. Execution happens asynchronously; the single remote push is amended
 * with the LLM response.
 */
public record PromptUpdatedEvent(PromptSpec promptSpec) {
}
