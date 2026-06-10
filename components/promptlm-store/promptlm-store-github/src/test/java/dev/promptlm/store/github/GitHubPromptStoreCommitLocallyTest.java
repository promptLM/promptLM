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

import dev.promptlm.domain.AppContext;
import dev.promptlm.domain.BasicAppContext;
import dev.promptlm.domain.ObjectMapperFactory;
import dev.promptlm.domain.projectspec.ProjectSpec;
import dev.promptlm.domain.promptspec.ChatCompletionRequest;
import dev.promptlm.domain.promptspec.ChatCompletionResponse;
import dev.promptlm.domain.promptspec.PromptSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Issue #352 invariant: the save flow must never write to the remote.
 *
 * <p>Under the Option-A deferred-push design ({@code commitLocally} stages only; the single
 * commit + push happens in {@code amendAndPushHead} once the LLM response is attached):
 * <ul>
 *   <li>{@link GitHubPromptStore#commitLocally(PromptSpec)} writes YAML and stages — but
 *   does NOT commit and does NOT push. No git history exists for the prompt until the
 *   executor's listener fires.</li>
 *   <li>{@link GitHubPromptStore#amendAndPushHead(PromptSpec)} writes the response-bearing
 *   YAML, creates a single commit, and pushes once. It does not amend any existing commit
 *   (the historical name is preserved to keep the API stable; the semantics changed when
 *   the HEAD-moving race caught by HappyPathUserJourneyTest.releasePrompt forced the move
 *   to stage-only saves).</li>
 * </ul>
 */
class GitHubPromptStoreCommitLocallyTest {

    @Test
    void commitLocallyStagesButDoesNotCommitOrPush(@TempDir Path repoDir) {
        Git git = mock(Git.class);
        GitHubPromptStore store = newStore(repoDir, git);
        PromptSpec spec = baseSpec();

        store.commitLocally(spec);

        // Save flow ensures the dev branch is checked out and stages all changes. No commit,
        // no push — those happen later, in amendAndPushHead, once the response is attached.
        verify(git).checkoutOrCreateBranch(anyString(), any(File.class));
        verify(git).stageAll(any(File.class));
        verify(git, never()).addAllAndCommit(any(File.class), anyString());
        verify(git, never()).pushAll(any(File.class));
    }

    @Test
    void amendAndPushHeadCommitsAndPushes(@TempDir Path repoDir) {
        Git git = mock(Git.class);
        GitHubPromptStore store = newStore(repoDir, git);
        PromptSpec executed = baseSpec().withResponse(new ChatCompletionResponse(12L, null, "answer"));

        store.amendAndPushHead(executed);

        // Single commit + push. NOT an amend — the stage-only commitLocally leaves no
        // commit to amend, so the deferred-push step writes the response-bearing commit
        // outright and pushes once. This sidesteps the HEAD-moving race.
        verify(git).addAllAndCommit(any(File.class), anyString());
        verify(git).pushAll(any(File.class));
        verify(git, never()).amendHead(any(File.class));
    }

    private static PromptSpec baseSpec() {
        return PromptSpec.builder()
                .withGroup("support")
                .withName("triage")
                .withVersion("1-SNAPSHOT")
                .withRevision(1)
                .withDescription("desc")
                .withRequest(ChatCompletionRequest.builder()
                        .withVendor("openai")
                        .withModel("gpt-4o")
                        .withMessages(List.of(ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("hi")
                                .build()))
                        .build())
                .build()
                .withId("support/triage");
    }

    private static GitHubPromptStore newStore(Path repoDir, Git git) {
        AppContext appContext = new BasicAppContext();
        appContext.setActiveProject(ProjectSpec.fromRepo(repoDir));
        return new GitHubPromptStore(
                ObjectMapperFactory.createYamlMapper(),
                new GitFileNameStrategy(),
                git,
                appContext,
                new IntVersioningStrategy(),
                new GitRepositoryMetadata(ObjectMapperFactory.createJsonMapper())
        );
    }
}
