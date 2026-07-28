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

import com.microsoft.playwright.Page;
import dev.promptlm.test.support.GiteaRepositoryHelper;
import dev.promptlm.test.support.NativeBinaryLauncher;
import dev.promptlm.test.support.PlaywrightNavigationHelper;
import dev.promptlm.test.support.ProjectSetupHelper;
import dev.promptlm.test.support.PromptWorkflowHelper;
import dev.promptlm.testutils.gitea.Gitea;
import dev.promptlm.testutils.gitea.GiteaContainer;
import dev.promptlm.testutils.gitea.WithGitea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Smoke test that drives the native webapp binary through real UI flows using Playwright.
 *
 * <p>This complements {@link NativeWebappSmokeTest} (API-level readiness only) by
 * exercising the actual user journey: create project → create prompt → push to Gitea.
 * Unlike {@link HappyPathUserJourneyTest}, this test deliberately avoids the release
 * gate and Artifactory dependencies, so it can run as a fast native-binary gate
 * without needing an LLM API key or an Artifactory container.
 */
@NativeSmokeTest
@WithGitea(createTestRepos = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NativeWebappUiSmokeTest {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String GROUP = "native-smoke-group";
    private static final String PROMPT_NAME = "native-smoke-prompt-" + System.currentTimeMillis();
    private static final String REPO_NAME = "native-smoke-repo-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    // Static @TempDir matches @TestInstance(PER_CLASS): JUnit populates the field before
    // @BeforeAll. Non-static @TempDir with PER_CLASS leaves the field null until the first
    // @BeforeEach, which is too late for the binary-startup work in @BeforeAll. Mirrors the
    // pattern used in HappyPathUserJourneyTest.
    @TempDir
    private static Path userHome;

    private GiteaContainer gitea;
    private NativeBinaryLauncher.RunningProcess runningProcess;
    private PlaywrightSession playwrightSession;
    private Page page;
    private String baseUrl;
    private int serverPort;

    @BeforeAll
    void beforeAll(@Gitea GiteaContainer giteaContainer) throws IOException {
        this.gitea = giteaContainer;
        Files.deleteIfExists(userHome.resolve(".promptlm/context.json"));

        serverPort = findFreePort();
        Map<String, String> systemProperties = new LinkedHashMap<>();
        systemProperties.put("gitea.url", giteaContainer.getWebUrl());
        systemProperties.put("REPO_REMOTE_URL", giteaContainer.getWebUrl() + "/api/v1");
        systemProperties.put("REPO_REMOTE_USERNAME", giteaContainer.getAdminUsername());
        systemProperties.put("REPO_REMOTE_TOKEN", giteaContainer.getAdminToken());
        systemProperties.put("promptlm.store.remote.endpoint", giteaContainer.getWebUrl() + "/api/v1");
        systemProperties.put("REPO_REMOTE_ALLOW_LOOPBACK_HOST_ALIASES", "true");

        runningProcess = NativeBinaryLauncher.startWebApplication(userHome, serverPort, systemProperties);
        baseUrl = "http://127.0.0.1:" + serverPort;

        awaitBackendReady();
        playwrightSession = PlaywrightSession.startPlaywrightSession(baseUrl);
        page = playwrightSession.getPage();
    }

    @AfterAll
    void afterAll() {
        // Run each shutdown step independently so a Playwright failure does not strand the
        // native process and vice versa.
        try {
            if (playwrightSession != null) {
                playwrightSession.endSession();
            }
        }
        catch (RuntimeException ignored) {
            // Best-effort cleanup; proceed.
        }
        try {
            if (playwrightSession != null) {
                playwrightSession.shutdown();
            }
        }
        catch (RuntimeException ignored) {
            // Best-effort cleanup; proceed.
        }
        if (runningProcess != null && runningProcess.process() != null) {
            Process process = runningProcess.process();
            process.destroy();
            try {
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(15, TimeUnit.SECONDS);
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("native webapp serves UI and creates a first project via Playwright")
    void createsFirstProjectViaUi() throws IOException {
        ProjectSetupHelper.verifyNoProjectExists(userHome);
        navigateToApp();
        ProjectSetupHelper.createNewProject(page, REPO_NAME, userHome);
        ProjectSetupHelper.assertRepositoryShownAsSelected(page, REPO_NAME);

        assertThat(userHome.resolve(REPO_NAME)).exists();
        assertThat(userHome.resolve(".promptlm/context.json").toFile()).isNotEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("native webapp creates a prompt and pushes its YAML to Gitea")
    void createsPromptAndPushesToGitea() {
        navigateToApp();
        navigateToPath("/prompts");

        PromptWorkflowHelper.createPromptViaForm(page, PromptWorkflowHelper.PromptCreationInput.builder()
                .name(PROMPT_NAME)
                .group(GROUP)
                .description("Native UI smoke prompt")
                .delimiters("[[", "]]")
                .placeholder("topic", "native")
                .userMessage("Describe [[topic]] briefly.")
                .build());

        navigateToPath("/prompts");
        page.waitForSelector("text=" + PROMPT_NAME);

        // Verify the prompt YAML was actually pushed to Gitea's development
        // branch. Saving triggers an LLM execution and the push is chained to
        // that execution succeeding, so this only passes with a working
        // OPENAI_API_KEY — which is why PromptWorkflowHelper selects the
        // openai vendor.
        await()
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Optional<String> yaml = GiteaRepositoryHelper.fetchRawFile(
                            HTTP_CLIENT,
                            giteaRepoBaseUrl(),
                            "development",
                            "prompts/" + GROUP + "/" + PROMPT_NAME + "/promptlm.yml",
                            gitea.getAdminToken());
                    assertThat(yaml).as("development branch must contain promptlm.yml for created prompt").isPresent();
                    assertThat(yaml.get())
                            .contains("name: " + PROMPT_NAME)
                            .contains("Describe [[topic]] briefly.");
                });
    }

    @Test
    @Order(3)
    @DisplayName("native webapp surfaces required-field validation on the prompt form")
    void validatesRequiredFields() {
        navigateToPath("/prompts/new");
        PromptWorkflowHelper.assertRequiredFieldValidation(page);
    }

    private void awaitBackendReady() {
        URI healthUri = URI.create(baseUrl + "/api/monitor/health");
        await()
                .atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    assertThat(runningProcess.process().isAlive())
                            .as("Native webapp process must remain alive during readiness probe")
                            .isTrue();
                    HttpResponse<String> response = httpGet(healthUri);
                    assertThat(response.statusCode()).isBetween(200, 299);
                    assertThat(response.body()).contains("\"status\":\"UP\"");
                });
    }

    private void navigateToApp() {
        playwrightSession.navigateToApp();
    }

    private void navigateToPath(String path) {
        PlaywrightNavigationHelper.navigateToPath(page, baseUrl, path);
    }

    private String giteaRepoBaseUrl() {
        return gitea.getWebUrl() + "/" + gitea.getAdminUsername() + "/" + REPO_NAME;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
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
            throw new AssertionError("HTTP probe failed: " + uri, e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted probing: " + uri, e);
        }
    }
}
