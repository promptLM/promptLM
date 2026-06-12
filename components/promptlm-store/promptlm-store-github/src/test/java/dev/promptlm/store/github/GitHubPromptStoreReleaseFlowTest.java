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

import dev.promptlm.domain.BasicAppContext;
import dev.promptlm.domain.ObjectMapperFactory;
import dev.promptlm.domain.projectspec.ProjectSpec;
import dev.promptlm.domain.promptspec.ChatCompletionRequest;
import dev.promptlm.domain.promptspec.PromptSpec;
import dev.promptlm.domain.promptspec.ReleaseMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.revwalk.RevCommit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubPromptStoreReleaseFlowTest {

    @Test
    void requestReleaseInPrModeCreatesBranchAndPullRequest(@TempDir Path repoDir) {
        RecordingGit git = new RecordingGit();
        StubPullRequestGateway pullRequestGateway = new StubPullRequestGateway();
        pullRequestGateway.nextCreated = new ReleasePullRequest(
                17,
                "https://github.com/promptLM/promptlm-app/pull/17",
                "open",
                false,
                "release/support-welcome-1.2.3",
                GitHubPromptStore.MAIN_BRANCH,
                null
        );

        GitHubPromptStore store = newStore(repoDir, ReleaseMetadata.MODE_PR_TWO_PHASE, git, pullRequestGateway);
        PromptSpec result = store.requestRelease(promptSpec("1.2.3-SNAPSHOT"));

        assertThat(result.getReleaseMetadata()).isNotNull();
        assertThat(result.getReleaseMetadata().state()).isEqualTo(ReleaseMetadata.STATE_REQUESTED);
        assertThat(result.getReleaseMetadata().mode()).isEqualTo(ReleaseMetadata.MODE_PR_TWO_PHASE);
        assertThat(result.getReleaseMetadata().branch()).isEqualTo("release/support-welcome-1.2.3");
        assertThat(result.getReleaseMetadata().prNumber()).isEqualTo(17);
        assertThat(result.getReleaseMetadata().existing()).isFalse();
        assertThat(pullRequestGateway.validateCalls).isEqualTo(1);
        assertThat(pullRequestGateway.createCalls).isEqualTo(1);
        assertThat(git.createdBranches).contains("release/support-welcome-1.2.3");
        assertThat(git.commitMessages).contains("Release requested");
    }

    @Test
    void requestReleaseInPrModeReturnsExistingWhenSameVersionAlreadyRequested(@TempDir Path repoDir) {
        RecordingGit git = new RecordingGit();
        StubPullRequestGateway pullRequestGateway = new StubPullRequestGateway();
        pullRequestGateway.openPullRequests = List.of(new ReleasePullRequest(
                22,
                "https://github.com/promptLM/promptlm-app/pull/22",
                "open",
                false,
                "release/support-welcome-1.2.3",
                GitHubPromptStore.MAIN_BRANCH,
                null
        ));

        GitHubPromptStore store = newStore(repoDir, ReleaseMetadata.MODE_PR_TWO_PHASE, git, pullRequestGateway);
        PromptSpec result = store.requestRelease(promptSpec("1.2.3-SNAPSHOT"));

        assertThat(result.getReleaseMetadata().state()).isEqualTo(ReleaseMetadata.STATE_REQUESTED);
        assertThat(result.getReleaseMetadata().existing()).isTrue();
        assertThat(result.getReleaseMetadata().prNumber()).isEqualTo(22);
        assertThat(pullRequestGateway.createCalls).isZero();
        assertThat(git.createdBranches).isEmpty();
        assertThat(git.commitMessages).isEmpty();
    }

    @Test
    void requestReleaseInPrModeRejectsConflictingPendingVersion(@TempDir Path repoDir) {
        RecordingGit git = new RecordingGit();
        StubPullRequestGateway pullRequestGateway = new StubPullRequestGateway();
        pullRequestGateway.openPullRequests = List.of(new ReleasePullRequest(
                41,
                "https://github.com/promptLM/promptlm-app/pull/41",
                "open",
                false,
                "release/support-welcome-1.2.2",
                GitHubPromptStore.MAIN_BRANCH,
                null
        ));

        GitHubPromptStore store = newStore(repoDir, ReleaseMetadata.MODE_PR_TWO_PHASE, git, pullRequestGateway);

        assertThatThrownBy(() -> store.requestRelease(promptSpec("1.2.3-SNAPSHOT")))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("Conflicting pending release request for prompt support/welcome");
        assertThat(pullRequestGateway.createCalls).isZero();
    }

    @Test
    void requestReleaseInPrModeReturnsReleasedWhenTagAlreadyExists(@TempDir Path repoDir) {
        RecordingGit git = new RecordingGit();
        git.tags.add("support/welcome-v1.2.3");
        StubPullRequestGateway pullRequestGateway = new StubPullRequestGateway();

        GitHubPromptStore store = newStore(repoDir, ReleaseMetadata.MODE_PR_TWO_PHASE, git, pullRequestGateway);
        PromptSpec result = store.requestRelease(promptSpec("1.2.3-SNAPSHOT"));

        assertThat(result.getReleaseMetadata().state()).isEqualTo(ReleaseMetadata.STATE_RELEASED);
        assertThat(result.getReleaseMetadata().mode()).isEqualTo(ReleaseMetadata.MODE_PR_TWO_PHASE);
        assertThat(result.getReleaseMetadata().existing()).isTrue();
        assertThat(pullRequestGateway.listCalls).isZero();
        assertThat(pullRequestGateway.createCalls).isZero();
    }

    @Test
    void requestReleaseInPrModeFailsWhenGatewayIsMissing(@TempDir Path repoDir) {
        RecordingGit git = new RecordingGit();
        GitHubPromptStore store = newStore(repoDir, ReleaseMetadata.MODE_PR_TWO_PHASE, git, null);

        assertThatThrownBy(() -> store.requestRelease(promptSpec("1.2.3-SNAPSHOT")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PR-mode release requires a pull request gateway implementation");
    }

    @Test
    void requestReleaseInDirectModePreservesReleaseAndSnapshotFlow(@TempDir Path repoDir) {
        RecordingGit git = new RecordingGit();
        GitHubPromptStore store = newStore(repoDir, ReleaseMetadata.MODE_DIRECT, git, null);

        PromptSpec result = store.requestRelease(promptSpec("1.2.3-SNAPSHOT"));

        assertThat(result.getReleaseMetadata().state()).isEqualTo(ReleaseMetadata.STATE_RELEASED);
        assertThat(result.getReleaseMetadata().mode()).isEqualTo(ReleaseMetadata.MODE_DIRECT);
        assertThat(result.getReleaseMetadata().existing()).isFalse();
        assertThat(result.getVersion()).isEqualTo("1.2.3");
        assertThat(git.cherryPickCalls).isEqualTo(1);
        assertThat(git.tags).contains("support/welcome-v1.2.3");
        assertThat(git.commitMessages).contains("Released", "Release support/welcome-v1.2.3", "New development version.");

        Optional<PromptSpec> latest = store.getLatestVersion("support/welcome");
        assertThat(latest).isPresent();
        assertThat(latest.orElseThrow().getVersion()).isEqualTo("1.2.4-SNAPSHOT");
    }

    @Test
    void completeReleaseIsOnlyAvailableInPrMode(@TempDir Path repoDir) {
        RecordingGit git = new RecordingGit();
        GitHubPromptStore store = newStore(repoDir, ReleaseMetadata.MODE_DIRECT, git, null);

        assertThatThrownBy(() -> store.completeRelease("support/welcome", "11"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only available when promptlm.release.promotion.mode=pr_two_phase");
    }

    @Test
    void completeReleaseRejectsUnmergedPullRequest(@TempDir Path repoDir) {
        RecordingGit git = new RecordingGit();
        StubPullRequestGateway pullRequestGateway = new StubPullRequestGateway();
        pullRequestGateway.pullRequests.put(11, new ReleasePullRequest(
                11,
                "https://github.com/promptLM/promptlm-app/pull/11",
                "open",
                false,
                "release/support-welcome-1.2.3",
                GitHubPromptStore.MAIN_BRANCH,
                null
        ));

        GitHubPromptStore store = newStore(repoDir, ReleaseMetadata.MODE_PR_TWO_PHASE, git, pullRequestGateway);
        store.saveToDisk(promptSpec("1.2.3-SNAPSHOT"));

        assertThatThrownBy(() -> store.completeRelease("support/welcome", "11"))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("has not been merged into main");
        assertThat(git.tags).isEmpty();
    }

    @Test
    void completeReleaseReturnsExistingWhenReleaseTagAlreadyExists(@TempDir Path repoDir) {
        RecordingGit git = new RecordingGit();
        git.tags.add("support/welcome-v1.2.3");

        StubPullRequestGateway pullRequestGateway = new StubPullRequestGateway();
        pullRequestGateway.pullRequests.put(11, new ReleasePullRequest(
                11,
                "https://github.com/promptLM/promptlm-app/pull/11",
                "closed",
                false,
                "release/support-welcome-1.2.3",
                GitHubPromptStore.MAIN_BRANCH,
                "abc123"
        ));

        GitHubPromptStore store = newStore(repoDir, ReleaseMetadata.MODE_PR_TWO_PHASE, git, pullRequestGateway);
        store.saveToDisk(promptSpec("1.2.3-SNAPSHOT"));

        PromptSpec result = store.completeRelease("support/welcome", "https://github.com/promptLM/promptlm-app/pull/11");

        assertThat(result.getReleaseMetadata().state()).isEqualTo(ReleaseMetadata.STATE_RELEASED);
        assertThat(result.getReleaseMetadata().existing()).isTrue();
        assertThat(result.getReleaseMetadata().prNumber()).isEqualTo(11);
        assertThat(git.commitMessages).isEmpty();
    }

    @Test
    void completeReleaseFinalizesMergedPrAndAdvancesDevelopmentSnapshot(@TempDir Path repoDir) {
        RecordingGit git = new RecordingGit();
        StubPullRequestGateway pullRequestGateway = new StubPullRequestGateway();
        pullRequestGateway.pullRequests.put(11, new ReleasePullRequest(
                11,
                "https://github.com/promptLM/promptlm-app/pull/11",
                "closed",
                true,
                "release/support-welcome-1.2.3",
                GitHubPromptStore.MAIN_BRANCH,
                "abc123"
        ));

        GitHubPromptStore store = newStore(repoDir, ReleaseMetadata.MODE_PR_TWO_PHASE, git, pullRequestGateway);
        store.saveToDisk(promptSpec("1.2.3-SNAPSHOT"));

        PromptSpec result = store.completeRelease("support/welcome", "11");

        assertThat(result.getReleaseMetadata().state()).isEqualTo(ReleaseMetadata.STATE_RELEASED);
        assertThat(result.getReleaseMetadata().mode()).isEqualTo(ReleaseMetadata.MODE_PR_TWO_PHASE);
        assertThat(result.getReleaseMetadata().existing()).isFalse();
        assertThat(result.getVersion()).isEqualTo("1.2.3");
        assertThat(git.tags).contains("support/welcome-v1.2.3");
        assertThat(git.commitMessages).contains("Release support/welcome-v1.2.3", "New development version.");

        Optional<PromptSpec> latest = store.getLatestVersion("support/welcome");
        assertThat(latest).isPresent();
        assertThat(latest.orElseThrow().getVersion()).isEqualTo("1.2.4-SNAPSHOT");
    }

    @Test
    void requestReleaseInPrModeIsSerializedPerPromptForConcurrentCalls(@TempDir Path repoDir) throws Exception {
        RecordingGit git = new RecordingGit();
        ConcurrencyPullRequestGateway pullRequestGateway = new ConcurrencyPullRequestGateway();
        GitHubPromptStore store = newStore(repoDir, ReleaseMetadata.MODE_PR_TWO_PHASE, git, pullRequestGateway);
        PromptSpec promptSpec = promptSpec("1.2.3-SNAPSHOT");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<PromptSpec> action = () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return store.requestRelease(promptSpec);
        };

        Future<PromptSpec> first = executor.submit(action);
        Future<PromptSpec> second = executor.submit(action);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<PromptSpec> results = new ArrayList<>();
        try {
            results.add(first.get(10, TimeUnit.SECONDS));
            results.add(second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(pullRequestGateway.createCalls.get()).isEqualTo(1);
        assertThat(results).anySatisfy(spec -> assertThat(spec.getReleaseMetadata().existing()).isFalse());
        assertThat(results).anySatisfy(spec -> assertThat(spec.getReleaseMetadata().existing()).isTrue());
    }

    private static GitHubPromptStore newStore(Path repoDir,
                                              String mode,
                                              RecordingGit git,
                                              ReleasePullRequestGateway pullRequestGateway) {
        ReleasePromotionProperties releaseProperties = new ReleasePromotionProperties();
        releaseProperties.setMode(mode);

        BasicAppContext appContext = new BasicAppContext();
        appContext.setActiveProject(ProjectSpec.fromRepo(repoDir));

        return new GitHubPromptStore(
                ObjectMapperFactory.createYamlMapper(),
                new GitFileNameStrategy(),
                git,
                appContext,
                new IntVersioningStrategy(),
                new GitRepositoryMetadata(ObjectMapperFactory.createJsonMapper()),
                releaseProperties,
                pullRequestGateway
        );
    }

    private static PromptSpec promptSpec(String version) {
        return PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion(version)
                .withRevision(7)
                .withDescription("Support prompt")
                .withRequest(ChatCompletionRequest.builder()
                        .withType(ChatCompletionRequest.TYPE)
                        .withVendor("openai")
                        .withModel("gpt-4o")
                        .withMessages(List.of())
                        .build())
                .build()
                .withId("support/welcome");
    }

    private static final class RecordingGit extends Git {
        private final List<String> commitMessages = new ArrayList<>();
        private final List<String> createdBranches = new ArrayList<>();
        private final Set<String> tags = new HashSet<>();
        private int cherryPickCalls;

        private RecordingGit() {
            super(
                    new EnvGitCredentialsProvider(gitHubProperties()),
                    new TrustedRemotePolicy(gitHubProperties()),
                    new BasicAppContext()
            );
        }

        @Override
        public String getFullName(Path repoDir) {
            return "promptLM/promptlm-app";
        }

        @Override
        public void checkoutBranch(String branchName, File repo) {
        }

        @Override
        public void resetHardAndClean(File repo) {
        }

        @Override
        public void checkoutOrCreateBranch(String branchName, File repo) {
            createdBranches.add(branchName);
        }

        @Override
        public RevCommit addAllAndCommit(File repo, String msg) {
            commitMessages.add(msg);
            return null;
        }

        @Override
        public void pushAll(File repo) {
        }

        @Override
        public void cherryPick(String devBranch, PromptSpec completed, File repo) {
            cherryPickCalls++;
        }

        @Override
        public boolean tagExists(String tag, File repo) {
            return tags.contains(tag);
        }

        @Override
        public void tagIfMissing(String tag, File repo) {
            tags.add(tag);
        }
    }

    private static final class StubPullRequestGateway implements ReleasePullRequestGateway {
        private List<ReleasePullRequest> openPullRequests = List.of();
        private final Map<Integer, ReleasePullRequest> pullRequests = new HashMap<>();
        private ReleasePullRequest nextCreated;
        private int validateCalls;
        private int createCalls;
        private int listCalls;

        @Override
        public ReleasePullRequest createReleasePullRequest(String repositoryFullName,
                                                           String headBranch,
                                                           String baseBranch,
                                                           String title,
                                                           String body) {
            createCalls++;
            if (nextCreated == null) {
                throw new IllegalStateException("nextCreated is not configured");
            }
            return nextCreated;
        }

        @Override
        public ReleasePullRequest getPullRequest(String repositoryFullName, int pullRequestNumber) {
            ReleasePullRequest pullRequest = pullRequests.get(pullRequestNumber);
            if (pullRequest == null) {
                throw new IllegalStateException("No PR configured for number " + pullRequestNumber);
            }
            return pullRequest;
        }

        @Override
        public List<ReleasePullRequest> listOpenPullRequests(String repositoryFullName, String baseBranch) {
            listCalls++;
            return openPullRequests;
        }

        @Override
        public void validatePrModeCapabilities(String repositoryFullName) {
            validateCalls++;
        }
    }

    private static final class ConcurrencyPullRequestGateway implements ReleasePullRequestGateway {
        private final AtomicInteger createCalls = new AtomicInteger();
        private final AtomicReference<ReleasePullRequest> existing = new AtomicReference<>();

        @Override
        public ReleasePullRequest createReleasePullRequest(String repositoryFullName,
                                                           String headBranch,
                                                           String baseBranch,
                                                           String title,
                                                           String body) {
            createCalls.incrementAndGet();
            try {
                Thread.sleep(75);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            ReleasePullRequest created = new ReleasePullRequest(
                    55,
                    "https://github.com/promptLM/promptlm-app/pull/55",
                    "open",
                    false,
                    headBranch,
                    baseBranch,
                    null
            );
            existing.set(created);
            return created;
        }

        @Override
        public ReleasePullRequest getPullRequest(String repositoryFullName, int pullRequestNumber) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public List<ReleasePullRequest> listOpenPullRequests(String repositoryFullName, String baseBranch) {
            ReleasePullRequest current = existing.get();
            return current == null ? List.of() : List.of(current);
        }

        @Override
        public void validatePrModeCapabilities(String repositoryFullName) {
        }
    }

    private static GitHubProperties gitHubProperties() {
        GitHubProperties properties = new GitHubProperties();
        properties.setBaseUrl("https://api.github.com");
        return properties;
    }
}
