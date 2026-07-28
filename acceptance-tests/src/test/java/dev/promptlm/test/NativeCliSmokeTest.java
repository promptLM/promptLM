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

package dev.promptlm.test;

import dev.promptlm.test.support.NativeBinaryLauncher;
import dev.promptlm.testutils.gitea.Gitea;
import dev.promptlm.testutils.gitea.GiteaContainer;
import dev.promptlm.testutils.gitea.WithGitea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@WithGitea(actionsEnabled = true)
@NativeSmokeTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NativeCliSmokeTest {

    private static final Duration CLI_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration STUDIO_START_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration STUDIO_STOP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration STUDIO_PROBE_TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @TempDir
    private static Path tempDir;

    private Path userHome;
    private Path workspaceRoot;
    private Map<String, String> nativeSystemProperties;
    private String repositoryName;
    private String repositorySlug;
    private String repositoryGitUrl;

    @BeforeEach
    void setUp(@Gitea GiteaContainer giteaContainer) throws IOException {
        String runId = UUID.randomUUID().toString().replace("-", "");
        repositoryName = "native-smoke-" + runId;
        repositorySlug = giteaContainer.getAdminUsername() + "/" + repositoryName;
        repositoryGitUrl = giteaContainer.getWebUrl() + "/" + repositorySlug + ".git";

        Path testRoot = tempDir.resolve("case-" + runId);
        userHome = testRoot.resolve("home");
        workspaceRoot = userHome.resolve("workspace");
        Files.createDirectories(workspaceRoot);

        nativeSystemProperties = new LinkedHashMap<>();
        nativeSystemProperties.put("REPO_REMOTE_URL", giteaContainer.getWebUrl() + "/api/v1");
        nativeSystemProperties.put("REPO_REMOTE_USERNAME", giteaContainer.getAdminUsername());
        nativeSystemProperties.put("REPO_REMOTE_TOKEN", giteaContainer.getAdminToken());
        nativeSystemProperties.put("promptlm.store.remote.endpoint", giteaContainer.getWebUrl() + "/api/v1");
        nativeSystemProperties.put("REPO_REMOTE_ALLOW_LOOPBACK_HOST_ALIASES", "true");
    }

    /**
     * Verifies repo creation succeeds with the native CLI binary.
     */
    @Test
    @Order(1)
    @DisplayName("repo create command succeeds")
    void shouldRunRepoCreateCommand() throws IOException {
        Path createdRepository = createRepository();
        assertThat(createdRepository.resolve(".git")).isDirectory();
    }

    /**
     * Verifies prompt creation succeeds with the native CLI binary.
     */
    @Test
    @Order(2)
    @DisplayName("prompt create command succeeds")
    void shouldRunPromptCreateCommand() throws IOException {
        createRepository();
        String promptId = createPrompt();
        assertThat(promptId).isNotBlank();
    }

    /**
     * Verifies prompt show succeeds with the native CLI binary.
     */
    @Test
    @Order(3)
    @DisplayName("prompt show command succeeds")
    void shouldRunPromptShowCommand() throws IOException {
        createRepository();
        String promptId = createPrompt();

        NativeBinaryLauncher.CommandResult showPrompt = runCliCommand(List.of("prompt", "show", "--id", promptId));
        assertCommandSucceeded(showPrompt);
        assertThat(showPrompt.output()).contains(promptId);
    }

    /**
     * Verifies prompt change command returns the expected deprecation message with the native CLI binary.
     */
    @Test
    @Order(4)
    @DisplayName("prompt change command succeeds with deprecation output")
    @org.junit.jupiter.api.Disabled("see promptLM/promptlm-app#392 — `prompt change` command + deprecation shim removed from CLI; test expectation is stale")
    void shouldRunPromptChangeCommand() throws IOException {
        createRepository();
        String promptId = createPrompt();

        NativeBinaryLauncher.CommandResult changePrompt = runCliCommand(
                List.of("prompt", "change", "--id", promptId, "--userMessage", "updated-smoke-prompt"));
        assertCommandSucceeded(changePrompt);
        assertThat(changePrompt.output()).contains("prompt change is no longer supported in the CLI");
    }

    /**
     * Verifies prompt release succeeds with the native CLI binary.
     */
    @Test
    @Order(5)
    @DisplayName("prompt release command succeeds")
    @org.junit.jupiter.api.Disabled("see promptLM/promptlm-app#393 — PreReleaseExecuteGate rejects prompts with empty vendor/model; either gate is too strict or test fixture is too thin")
    void shouldRunPromptReleaseCommand() throws IOException {
        createRepository();
        String promptId = createPrompt();

        NativeBinaryLauncher.CommandResult releasePrompt = runCliCommand(List.of("prompt", "release", "--id", promptId));
        assertCommandSucceeded(releasePrompt);
        String releaseLine = lastNonBlankLine(releasePrompt.output());
        assertThat(releaseLine)
                .as("Release output should contain either a release version, a next development version, or a PR request marker")
                .matches("^(requested\\s+\\S+\\s+pr#\\d+|\\d+\\.\\d+\\.\\d+(?:-SNAPSHOT)?)$");
    }

    /**
     * Verifies repo use succeeds with the native CLI binary.
     */
    @Test
    @Order(6)
    @DisplayName("repo use command succeeds")
    void shouldRunRepoUseCommand() throws IOException {
        Path createdRepository = createRepository();

        NativeBinaryLauncher.CommandResult useRepository = runCliCommand(
                List.of("repo", "use", "--path", createdRepository.toString()));
        assertCommandSucceeded(useRepository);
        assertThat(useRepository.output()).contains("using repo");
    }

    /**
     * Verifies repo clone succeeds with the native CLI binary.
     */
    @Test
    @Order(7)
    @DisplayName("repo clone command succeeds")
    void shouldRunRepoCloneCommand() throws IOException {
        createRepository();

        Path cloneTargetRoot = workspaceRoot.resolve("cloned-repos");
        Files.createDirectories(cloneTargetRoot);
        NativeBinaryLauncher.CommandResult cloneRepository = runCliCommand(
                List.of("repo", "clone", "--repo", repositoryGitUrl, "--target", cloneTargetRoot.toString()));
        assertCommandSucceeded(cloneRepository);
        assertThat(cloneRepository.output()).contains("using repo");
        assertThat(cloneTargetRoot.resolve(repositoryName).resolve(".git")).isDirectory();
    }

    /**
     * Verifies studio command starts in the foreground and releases the bound port when stopped.
     */
    @Test
    @Order(8)
    @DisplayName("studio command succeeds and stays foreground")
    void shouldRunStudioCommand() throws IOException {
        createRepository();

        int studioPort = findFreePort();
        NativeBinaryLauncher.RunningProcess runningStudio = NativeBinaryLauncher.startCliCommand(
                userHome,
                nativeSystemProperties,
                List.of("studio", "--port", String.valueOf(studioPort), "--no-browser", "true"),
                workspaceRoot
        );
        Process studioProcess = runningStudio.process();
        try {
            String startupOutput = waitForOutputContains(studioProcess, "PromptLM Studio is running at", STUDIO_START_TIMEOUT);
            assertThat(startupOutput).contains("PromptLM Studio is running at");
            assertThat(studioProcess.isAlive())
                    .as("Studio command should keep the CLI process in the foreground")
                    .isTrue();

            // Liveness of the JVM is necessary but not sufficient — the embedded web server
            // must actually answer. Probe both readiness endpoints before treating the test
            // as passed. This catches the regression where the JVM stays up but the context
            // tore down (or never came up).
            URI healthUri = URI.create("http://127.0.0.1:" + studioPort + "/api/monitor/health");
            URI capabilitiesUri = URI.create("http://127.0.0.1:" + studioPort + "/api/capabilities");

            await()
                    .atMost(STUDIO_PROBE_TIMEOUT)
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        assertThat(studioProcess.isAlive())
                                .as("Studio process must remain alive while probing the backend")
                                .isTrue();
                        HttpResponse<String> healthResponse = httpGet(healthUri);
                        assertThat(healthResponse.statusCode()).isBetween(200, 299);
                        assertThat(healthResponse.body()).contains("\"status\":\"UP\"");
                    });

            await()
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        HttpResponse<String> capabilitiesResponse = httpGet(capabilitiesUri);
                        assertThat(capabilitiesResponse.statusCode()).isBetween(200, 299);
                        assertThat(capabilitiesResponse.body()).contains("\"features\"");
                    });
        }
        finally {
            stopProcess(studioProcess);
        }

        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> isPortAvailable(studioPort));
    }

    private Path createRepository() throws IOException {
        Path createdReposRoot = workspaceRoot.resolve("created-repos");
        Files.createDirectories(createdReposRoot);

        NativeBinaryLauncher.CommandResult createRepository = runCliCommand(
                List.of("repo", "create", "--dir", createdReposRoot.toString(), "--name", repositorySlug));
        assertCommandSucceeded(createRepository);
        assertThat(createRepository.output()).contains("created repository");

        Path createdRepository = createdReposRoot.resolve(repositoryName);
        assertThat(createdRepository.resolve(".git")).isDirectory();
        assertThat(createdRepository.resolve(".promptlm")).isDirectory();
        return createdRepository;
    }

    private String createPrompt() {
        NativeBinaryLauncher.CommandResult createPrompt = runCliCommand(
                List.of("prompt", "create",
                        "--name", "native-cli-prompt",
                        "--group", "native-smoke",
                        "--userMessage", "native-smoke-prompt",
                        "--placeholder", "topic=native"));
        assertCommandSucceeded(createPrompt);

        String promptId = lastNonBlankLine(createPrompt.output());
        assertThat(promptId).isNotBlank();
        return promptId;
    }

    private NativeBinaryLauncher.CommandResult runCliCommand(List<String> arguments) {
        return NativeBinaryLauncher.runCliCommand(userHome, nativeSystemProperties, arguments, CLI_TIMEOUT);
    }

    private static void assertCommandSucceeded(NativeBinaryLauncher.CommandResult commandResult) {
        assertThat(commandResult.exitCode())
                .as("Native command exit code for %s from binary %s%nOutput:%n%s",
                        commandResult.command(),
                        commandResult.binaryPath(),
                        commandResult.output())
                .isZero();
        assertThat(commandResult.output()).isNotBlank();
        assertThat(commandResult.output()).doesNotContain("Command not found");
    }

    private static String lastNonBlankLine(String output) {
        String[] lines = output.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static String waitForOutputContains(Process process, String expectedFragment, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        StringBuilder output = new StringBuilder();
        InputStream inputStream = process.getInputStream();
        while (System.nanoTime() < deadline) {
            while (inputStream.available() > 0) {
                output.append((char) inputStream.read());
            }
            String collected = output.toString();
            if (collected.contains(expectedFragment)) {
                return collected;
            }
            if (!process.isAlive()) {
                break;
            }
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for native studio startup output", e);
            }
        }
        while (inputStream.available() > 0) {
            output.append((char) inputStream.read());
        }
        throw new IllegalStateException(
                "Timed out waiting for native studio startup output containing '"
                        + expectedFragment + "'. Output: " + output.toString().trim());
    }

    private static void stopProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(STUDIO_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(STUDIO_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        }
        catch (IOException ignored) {
            return false;
        }
    }

    private static HttpResponse<String> httpGet(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException e) {
            throw new AssertionError("HTTP probe failed while waiting for studio backend: " + uri, e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while probing studio backend: " + uri, e);
        }
    }
}
