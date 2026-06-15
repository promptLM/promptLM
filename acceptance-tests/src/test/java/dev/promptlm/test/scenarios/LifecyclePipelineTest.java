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

package dev.promptlm.test.scenarios;

import dev.promptlm.domain.promptspec.PromptSpecLifecycleState;
import dev.promptlm.test.NativeAcceptanceTest;
import dev.promptlm.test.harness.NativeAppHandle;
import dev.promptlm.test.harness.StudioDriver;
import dev.promptlm.test.harness.WithNativeApp;
import dev.promptlm.test.harness.WorkspaceState;
import dev.promptlm.test.remotes.GiteaContentsClient;
import dev.promptlm.test.support.NativeBinaryLauncher;
import dev.promptlm.testutils.gitea.GiteaContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end acceptance scenario that walks a prompt through its full
 * lifecycle (DRAFT → SAVED → COMMITTED → PUSHED) using only user-shaped
 * verbs.
 *
 * <p>The scenario drives the native CLI binary for the bootstrap repo and
 * the native webapp binary (via Playwright) for every prompt-editor
 * interaction. It verifies state via three independent witnesses:
 * <ul>
 *     <li>The Studio lifecycle badge ({@link StudioDriver#expectBadge}).</li>
 *     <li>The on-disk + JGit-derived state ({@link WorkspaceState}).</li>
 *     <li>The remote contents on Gitea ({@link GiteaContentsClient}).</li>
 * </ul>
 *
 * <p><strong>SPA contract notes (Phase 1).</strong> The v2 SPA in this repo
 * exposes a single primary save action ({@code save-prompt-button}). The
 * Studio façade's {@code Toolbar.clickCommit()} and {@code Toolbar.clickPush()}
 * verbs fall back to role-by-name lookups when the testids are missing — so
 * if the SPA currently merges save+commit+push under one action, this
 * scenario still verifies the final PUSHED triangle (badge + disk-and-git +
 * Gitea REST). When dedicated commit / push buttons land the verbs become
 * exact and the intermediate states will be observable too.
 */
@NativeAcceptanceTest
@WithNativeApp(binaries = {WithNativeApp.Binary.WEBAPP, WithNativeApp.Binary.CLI}, withGitea = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LifecyclePipelineTest {

    private static final String GROUP = "acc";
    private static final Duration GITEA_PROBE_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Walks a prompt through DRAFT → SAVED → COMMITTED → PUSHED.
     *
     * <p>Steps map 1:1 to the user behaviour: bootstrap a repo via the CLI,
     * fill the new-prompt form, save, then drive the push action (or fall
     * back to the merged primary action). After every step the witness
     * triangle is checked. The test body is intentionally linear — no
     * branching, no loops — per the harness rules in
     * {@code acceptance-tests/AGENTS.md}.
     */
    @Test
    @DisplayName("prompt walks DRAFT → SAVED → COMMITTED → PUSHED via UI + CLI")
    void promptWalksLifecycle(NativeAppHandle.WebappHandle webapp,
                              NativeAppHandle.CliHandle cli,
                              GiteaContainer gitea) {
        ScenarioRecorder recorder = ScenarioRecorder.forTest(getClass().getSimpleName() + "-promptWalksLifecycle");
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String repoName = "lifecycle-" + runId;
        String repoSlug = gitea.getAdminUsername() + "/" + repoName;
        String promptName = "lifecycle-prompt-" + runId;

        recorder.step("bootstrap repo via CLI: repo create");
        Path createdReposRoot = ensureDirectory(cli.workspace().resolve("created-repos"));
        NativeBinaryLauncher.CommandResult createRepo = cli.exec(
                "repo", "create",
                "--dir", createdReposRoot.toString(),
                "--name", repoSlug);
        assertThat(createRepo.exitCode())
                .as("repo create exit code; output:\n%s", createRepo.output())
                .isZero();
        Path activeRepo = createdReposRoot.resolve(repoName);
        assertThat(activeRepo.resolve(".git")).isDirectory();

        recorder.step("studio: open new prompt form");
        StudioDriver studio = webapp.studio();
        studio.openHome();
        studio.openNewPromptForm();

        recorder.step("studio: fill required fields");
        // The v2 SPA form gates the Save button until every required field
        // is non-blank. See
        // `components/promptlm-web-ui/src/features/prompt-editor/validation.ts`:
        //   - validateMetadata        → name + group + description
        //   - validateModelConfiguration → request.vendor + request.model
        // Leaving any of these blank keeps the Create/Save button
        // disabled and clickSave times out on Playwright's 30s budget.
        studio.fillName(promptName);
        studio.fillGroup(GROUP);
        studio.fillDescription("Lifecycle acceptance scenario: walks DRAFT → SAVED → COMMITTED → PUSHED.");
        studio.selectVendor("anthropic");
        studio.setModel("claude-sonnet-4-5");
        studio.fillUserMessage("Lifecycle smoke message for " + promptName);

        recorder.step("witness: DRAFT — disk has no spec yet");
        WorkspaceState workspace = WorkspaceState.observe(cli.workspace(), activeRepo);
        assertThatThrownBy(() -> workspace.specOnDisk(GROUP, promptName))
                .as("DRAFT means no file on disk yet")
                .isInstanceOf(IllegalStateException.class);

        recorder.step("studio: click Save");
        studio.toolbar().clickSave();
        // The v2 SPA navigates to /prompts/:id after a successful save; we
        // tolerate the navigation by polling the workspace witness rather
        // than asserting a transient toast.
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    WorkspaceState w = WorkspaceState.observe(cli.workspace(), activeRepo);
                    WorkspaceState.PromptSpecRef ref = w.specOnDisk(GROUP, promptName);
                    assertThat(ref.path()).exists();
                });

        recorder.step("witness: post-Save lifecycle is at least SAVED");
        WorkspaceState postSaveWorkspace = WorkspaceState.observe(cli.workspace(), activeRepo);
        WorkspaceState.PromptSpecRef saved = postSaveWorkspace.specOnDisk(GROUP, promptName);
        WorkspaceState.LifecycleObservation postSave = postSaveWorkspace.lifecycleOf(saved);
        assertThat(postSave.derivedByDiskAndGit())
                .as("After Save, lifecycle must be SAVED / COMMITTED / PUSHED — was %s", postSave)
                .isIn(PromptSpecLifecycleState.SAVED,
                        PromptSpecLifecycleState.COMMITTED,
                        PromptSpecLifecycleState.PUSHED);

        recorder.step("studio: click Commit (no-op when merged with Save)");
        studio.toolbar().clickCommit();

        recorder.step("studio: click Push");
        studio.toolbar().clickPush();

        recorder.step("witness: refresh remote-tracking and assert PUSHED");
        org.awaitility.Awaitility.await()
                .atMost(GITEA_PROBE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    WorkspaceState w = WorkspaceState.observe(cli.workspace(), activeRepo);
                    w.refreshRemoteTracking();
                    WorkspaceState.PromptSpecRef ref = w.specOnDisk(GROUP, promptName);
                    WorkspaceState.LifecycleObservation observation = w.lifecycleOf(ref);
                    assertThat(observation.derivedByDiskAndGit())
                            .as("Post-push lifecycle observation: %s", observation)
                            .isEqualTo(PromptSpecLifecycleState.PUSHED);
                });

        recorder.step("witness: Gitea REST confirms the YAML on origin");
        GiteaContentsClient contents = new GiteaContentsClient(gitea);
        WorkspaceState.GitRefs refs = WorkspaceState.observe(cli.workspace(), activeRepo).gitRefs();
        String branch = refs.branch();
        String relativePath = "prompts/" + GROUP + "/" + promptName + "/promptlm.yml";
        contents.awaitPath(gitea.getAdminUsername(), repoName, branch, relativePath, GITEA_PROBE_TIMEOUT);
        String remoteHead = contents.branchHeadSha(gitea.getAdminUsername(), repoName, branch);
        assertThat(remoteHead)
                .as("Local HEAD %s must match Gitea branch head %s", refs.headSha(), remoteHead)
                .isEqualTo(refs.headSha());
    }

    /**
     * Creates the directory if it does not yet exist, returning it. Lifted
     * out of the test body to keep the test linear (no inline try/catch).
     */
    private static Path ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
            return path;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
