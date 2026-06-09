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

package dev.promptlm.store.github;

import dev.promptlm.lifecycle.application.PromptStorePort;
import dev.promptlm.domain.promptspec.PromptSpec;
import dev.promptlm.store.api.PromptStore;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class PromptStoreAdapter implements PromptStorePort {

    private final PromptStore promptStore;

    PromptStoreAdapter(PromptStore promptStore) {
        this.promptStore = promptStore;
    }

    @Override
    public Optional<PromptSpec> findPromptSpec(String group, String name) {
        return promptStore.findPromptSpec(group, name);
    }

    @Override
    public String findPromptSpecTemplate(String group) {
        return promptStore.findPromptSpecTemplate(group);
    }

    @Override
    public PromptSpec storePrompt(PromptSpec promptSpec) {
        return promptStore.storePrompt(promptSpec);
    }

    @Override
    public PromptSpec storePromptDraft(PromptSpec promptSpec) {
        return promptStore.storePromptDraft(promptSpec);
    }

    @Override
    public Optional<PromptSpec> getLatestVersion(String promptSpecId) {
        return promptStore.getLatestVersion(promptSpecId);
    }

    @Override
    public PromptSpec requestRelease(PromptSpec promptSpec) {
        return promptStore.requestRelease(promptSpec);
    }

    @Override
    public PromptSpec completeRelease(String promptSpecId, String pullRequestReference) {
        return promptStore.completeRelease(promptSpecId, pullRequestReference);
    }
}
