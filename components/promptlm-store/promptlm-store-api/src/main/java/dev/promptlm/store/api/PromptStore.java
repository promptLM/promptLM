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

package dev.promptlm.store.api;

import dev.promptlm.domain.promptspec.PromptSpec;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing and versioning prompts.
 */
public interface PromptStore {

    /**
     * Stores a new prompt specification with initial metadata.
     *
     * @param promptSpec The prompt specification to store.
     * @return The unique identifier for the stored prompt.
     */
    PromptSpec storePrompt(PromptSpec promptSpec);

    /**
     * Persist {@code promptSpec} locally (write YAML, stage, commit) without pushing to the
     * remote. Used by the save flow under issue #352 so that the remote only ever sees the
     * with-response version of the spec.
     *
     * <p>Default implementation delegates to {@link #storePrompt(PromptSpec)} for backends
     * that do not distinguish local vs. remote writes (e.g. in-memory test fakes).
     *
     * @param promptSpec the spec to persist
     * @return the spec as actually persisted (with path, timestamps, etc. populated)
     */
    default PromptSpec commitLocally(PromptSpec promptSpec) {
        return storePrompt(promptSpec);
    }

    /**
     * Rewrite the given {@code promptSpec}'s YAML on disk, amend the most recent local commit
     * on HEAD with those contents, and push HEAD to its tracked remote. Called by the
     * post-execution push listener (issue #352) once the LLM response has been attached.
     *
     * <p>The amend-then-push pair guarantees the remote sees exactly one commit per save —
     * the with-response version — even if the user saves and the LLM fails several times
     * before eventually succeeding.
     *
     * <p>Default implementation delegates to {@link #storePrompt(PromptSpec)} for backends
     * that do not distinguish local vs. remote writes (e.g. in-memory test fakes).
     */
    default PromptSpec amendAndPushHead(PromptSpec promptSpec) {
        return storePrompt(promptSpec);
    }

    /**
     * Retrieves the latest version of a prompt specification by ID.
     *
     * @param promptId The unique identifier of the prompt.
     * @return The latest version of the prompt specification, if it exists.
     */
    Optional<PromptSpec> getLatestVersion(String promptId);

    /**
     * Retrieves a specific version of a prompt specification by ID and version number.
     *
     * @param promptId       The unique identifier of the prompt.
     * @param versionNumber  The version number of the prompt to retrieve.
     * @return               The specified version of the prompt specification, if it exists.
     */
    Optional<PromptSpec> getPromptVersion(String promptId, int versionNumber);

    /**
     * Lists all versions of a prompt specification by ID.
     *
     * @param promptId      The unique identifier of the prompt.
     * @return              A list of all versions for the prompt, ordered by version.
     */
    List<PromptSpec> listVersions(String promptId);

    /**
     * Updates an existing prompt by storing a new version with updated content and metadata.
     *
     * @param promptId      The unique identifier of the prompt.
     * @param promptSpec    The updated prompt specification.
     * @return              The version number of the newly created version.
     */
    int updatePrompt(String promptId, PromptSpec promptSpec);

    /**
     * Deletes a specific version of a prompt specification by ID and version number.
     *
     * @param promptId       The unique identifier of the prompt.
     * @param versionNumber  The version number to delete.
     * @return               True if deletion was successful; false otherwise.
     */
    @Deprecated
    boolean deletePromptVersion(String promptId, int versionNumber);

    /**
     * Deletes all versions of a prompt specification by ID.
     *
     * @param promptId      The unique identifier of the prompt.
     * @return              True if deletion was successful; false otherwise.
     */
    @Deprecated
    boolean deleteAllVersions(String promptId);

    /**
     * Returns the template for the given group.
     *
     * @param group The group to search for
     */
    String findPromptSpecTemplate(String group);

    /**
     * Create a new release with incremented version number.
     */
    PromptSpec requestRelease(PromptSpec completed);

    /**
     * Finalize a previously requested release after promotion to main has been confirmed.
     */
    PromptSpec completeRelease(String promptSpecId, String pullRequestReference);

    /**
     * Backwards-compatible alias for requesting a release.
     */
    default PromptSpec release(PromptSpec completed) {
        return requestRelease(completed);
    }

    Optional<PromptSpec> findPromptSpec(String group, String name);

    /**
     * Lists all git-history revisions for the given prompt, newest first.
     *
     * <p>Each entry corresponds to a single commit that touched the prompt's
     * spec file. Implementations are expected to derive {@link Revision#kind()}
     * from the commit's diff against its parent.
     *
     * <p>Default implementation returns an empty list — backends that do not
     * track revisions can leave it unimplemented.
     *
     * @param group the prompt group
     * @param name  the prompt name
     * @return a list of revisions, newest first; empty if the prompt has no
     *         history or the backend does not track it
     * @throws IllegalArgumentException if {@code group} or {@code name} contains
     *         characters that are not safe as path segments
     */
    default List<Revision> listRevisions(String group, String name) {
        return List.of();
    }

    /**
     * Lists all prompts in the store.
     * 
     * @return A list of all prompt specifications.
     */
    List<PromptSpec> listAllPrompts();

    /**
     * Lists prompts in the store with optional retired entries.
     *
     * @param includeRetired Whether to include retired prompts.
     * @return A list of prompt specifications.
     */
    List<PromptSpec> listAllPrompts(boolean includeRetired);

    PromptSpec getDevelopmentVersion(String id);
}
