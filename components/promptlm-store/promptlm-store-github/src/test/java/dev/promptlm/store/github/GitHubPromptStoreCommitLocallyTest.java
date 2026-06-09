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
 * Issue #352 invariant: the save flow must never push.
 *
 * <p>{@link GitHubPromptStore#commitLocally(PromptSpec)} writes YAML, stages, and commits —
 * but it MUST NOT invoke {@link Git#pushAll(File)}. The push only happens once, later, in
 * the post-execution {@code amendAndPushHead(...)} branch.
 */
class GitHubPromptStoreCommitLocallyTest {

    @Test
    void commitLocallyWritesAndCommitsButDoesNotPush(@TempDir Path repoDir) {
        Git git = mock(Git.class);
        GitHubPromptStore store = newStore(repoDir, git);
        PromptSpec spec = baseSpec();

        store.commitLocally(spec);

        // The save flow stages + commits its own message. Local-only — no push.
        verify(git).checkoutOrCreateBranch(anyString(), any(File.class));
        verify(git).addAllAndCommit(any(File.class), anyString());
        verify(git, never()).pushAll(any(File.class));
    }

    @Test
    void amendAndPushHeadAmendsThenPushes(@TempDir Path repoDir) {
        Git git = mock(Git.class);
        GitHubPromptStore store = newStore(repoDir, git);
        PromptSpec executed = baseSpec().withResponse(new ChatCompletionResponse(12L, null, "answer"));

        store.amendAndPushHead(executed);

        verify(git).amendHead(any(File.class));
        verify(git).pushAll(any(File.class));
        // amendAndPushHead must NEVER produce a second (non-amend) commit.
        verify(git, never()).addAllAndCommit(any(File.class), anyString());
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
