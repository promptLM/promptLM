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

package dev.promptlm.lifecycle.application;

import dev.promptlm.domain.promptspec.PromptSpec;

import java.util.Optional;

/**
 * Port for persisting and retrieving prompt specifications from the backing store.
 */
public interface PromptStorePort {
    Optional<PromptSpec> findPromptSpec(String group, String name);

    String findPromptSpecTemplate(String group);

    PromptSpec storePrompt(PromptSpec promptSpec);

    /**
     * Local-only persistence (write YAML + git commit, no push). Used by the save flow
     * under issue #352. Default delegates to {@link #storePrompt(PromptSpec)} so test
     * doubles continue to work unchanged.
     */
    default PromptSpec commitLocally(PromptSpec promptSpec) {
        return storePrompt(promptSpec);
    }

    /**
     * Rewrite {@code promptSpec}'s YAML on disk, amend the most recent local commit, and push
     * HEAD upstream. Called from the post-execution push listener (issue #352). Default
     * delegates to {@link #storePrompt(PromptSpec)} so test doubles continue to work.
     */
    default PromptSpec amendAndPushHead(PromptSpec promptSpec) {
        return storePrompt(promptSpec);
    }

    Optional<PromptSpec> getLatestVersion(String promptSpecId);

    PromptSpec requestRelease(PromptSpec promptSpec);

    PromptSpec completeRelease(String promptSpecId, String pullRequestReference);

    default PromptSpec release(PromptSpec promptSpec) {
        return requestRelease(promptSpec);
    }
}
