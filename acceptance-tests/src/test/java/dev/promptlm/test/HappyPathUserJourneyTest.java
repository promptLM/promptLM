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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import dev.promptlm.domain.ObjectMapperFactory;
import dev.promptlm.domain.promptspec.ChatCompletionResponse;
import dev.promptlm.domain.promptspec.Execution;
import dev.promptlm.domain.promptspec.ExecutionKind;
import dev.promptlm.domain.promptspec.PromptSpec;
import dev.promptlm.test.support.ArtifactoryStorageHelper;
import dev.promptlm.test.support.GiteaRepositoryHelper;
import dev.promptlm.test.support.PlaywrightNavigationHelper;
import dev.promptlm.test.support.ProjectSetupHelper;
import dev.promptlm.test.support.ReleaseArtifactContractDelegate;
import dev.promptlm.testutils.PollingHelper;
import dev.promptlm.testutils.artifactory.Artifactory;
import dev.promptlm.testutils.artifactory.ArtifactoryContainer;
import dev.promptlm.testutils.artifactory.WithArtifactory;
import dev.promptlm.testutils.gitea.Gitea;
import dev.promptlm.testutils.gitea.GiteaContainer;
import dev.promptlm.testutils.gitea.WithGitea;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the prompt creation functionality using Playwright
 * Tests the complete flow through frontend and backend
 * <p>
 */
@WithGitea(createTestRepos = true, actionsEnabled = true)
@WithArtifactory
@IntegrationTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(HappyPathUserJourneyTest.ScreenshotOnFailureExtension.class)
public class HappyPathUserJourneyTest {

    private static final Logger log = LoggerFactory.getLogger(HappyPathUserJourneyTest.class);

    public static final String PROMPT_NAME = "test-prompt-" + System.currentTimeMillis();
    public static final String REPO_NAME = "test-repo-" + System.currentTimeMillis();
    public static final String GROUP = "test-group";
    private static GiteaContainer gitea;
    private static ArtifactoryContainer artifactoryContainer;
    private static volatile boolean artifactoryVariablesConfigured;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @TempDir
    private static Path userHome;
    protected Page page;
    private PlaywrightSession playwrightSession;
    private String repositoryOwner;
    private String baseUrl;

    @BeforeAll
    void beforeAll(@Gitea GiteaContainer giteaContainer, @Artifactory ArtifactoryContainer artifactory) throws IOException {
        // Store the Gitea container instance provided by @WithGitea
        this.gitea = giteaContainer;
        HappyPathUserJourneyTest.artifactoryContainer = artifactory;
        this.repositoryOwner = gitea.getAdminUsername();

        // Use the Gitea container provided by @WithGitea annotation
        String giteaUrl = gitea.getWebUrl();
        String giteaUsername = gitea.getAdminUsername();
        String giteaToken = gitea.getAdminToken();

        Files.deleteIfExists(userHome.resolve(".promptlm/context.json"));
        baseUrl = TestApplicationManager.startApplicationWithGitea(userHome, giteaUrl, giteaUsername, giteaToken);
        playwrightSession = PlaywrightSession.startPlaywrightSession(baseUrl);
        page = playwrightSession.getPage();
    }


    @AfterAll
    void afterAll() {
        TestApplicationManager.stopApplication();
        if (playwrightSession != null) {
            playwrightSession.endSession();
            playwrightSession.shutdown();
        }
    }

    @Test
    @Order(1)
    @DisplayName("create first project")
    void createFirstProject() throws IOException, InterruptedException {
        ProjectSetupHelper.verifyNoProjectExists(getUserHome());
        navigateToApplication();
        ProjectSetupHelper.createNewProject(page, REPO_NAME, getUserHome());
        ProjectSetupHelper.assertRepositoryShownAsSelected(page, REPO_NAME);
        verifyProjectExists();
    }

    @Test
    @Order(20)
    @DisplayName("Should be able to create a new prompt")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void shouldCreateNewPrompt() {
        page.setViewportSize(1280, 720);
        navigateToApplication();
        ensureRepositoryVariablesConfigured();

        // v2 UX: there is no global "Add" dropdown — the New prompt button lives in
        // the catalog top bar. Navigate to /prompts and click it directly.
        navigateToPath("/prompts");
        page.getByTestId("create-prompt-button").click();

        // Verify we're in the prompt editor via unique heading test id
        Locator editorHeading = page.getByTestId("prompt-editor-heading");
        editorHeading.waitFor();
        assertThat(editorHeading.isVisible()).isTrue();

        // Fill the form
        page.getByTestId("prompt-name-input").fill(PROMPT_NAME);
        page.getByTestId("prompt-group-input").fill(GROUP);
        page.getByTestId("description-text").fill("Description of the prompt.");
        configureCustomPlaceholderDelimiters("[[", "]]");
        addPlaceholder("number_one");
        addPlaceholder("number_two");
        setPlaceholderValue("[[number_one]]", "1");
        setPlaceholderValue("[[number_two]]", "2");

        // add user message
        page.getByTestId("user-prompt-button").click();
        Locator promptTextarea = page.getByTestId("prompt-messages").locator("textarea").last();
        promptTextarea.fill("Number one ");
        insertPlaceholderToken("number_one");
        promptTextarea = page.getByTestId("prompt-messages").locator("textarea").last();
        promptTextarea.click();
        page.keyboard().press("End");
        page.keyboard().type(" plus number two ");
        insertPlaceholderToken("number_two");
        promptTextarea = page.getByTestId("prompt-messages").locator("textarea").last();
        promptTextarea.click();
        page.keyboard().press("End");
        page.keyboard().type(" equals?");

        // Click the save button
        page.getByTestId("save-prompt-button").click();

        // After a successful create, the v2 shell navigates from /prompts/new to
        // the new detail page (/prompts/:id). Wait for that URL transition rather
        // than the transient "Prompt created" toast, which the navigation can
        // unmount before Playwright sees it. We must explicitly exclude
        // /prompts/new (the starting URL) — a generic /prompts/<token> regex
        // matches it and would return immediately.
        page.waitForURL(
                url -> {
                    if (url == null) return false;
                    int promptsIdx = url.indexOf("/prompts/");
                    if (promptsIdx < 0) return false;
                    String tail = url.substring(promptsIdx + "/prompts/".length());
                    int slash = tail.indexOf('/');
                    String segment = slash < 0 ? tail : tail.substring(0, slash);
                    int q = segment.indexOf('?');
                    if (q >= 0) segment = segment.substring(0, q);
                    int h = segment.indexOf('#');
                    if (h >= 0) segment = segment.substring(0, h);
                    return !segment.isEmpty() && !"new".equals(segment);
                },
                new Page.WaitForURLOptions().setTimeout(60_000));
        navigateToPath("/prompts");
        page.waitForSelector("text=" + PROMPT_NAME);

        gitea.logRepositoryActionsDiagnostics(repositoryOwner, REPO_NAME);

        // Navigate to Gitea and sign in via helper
        navigateToGitea();
        GiteaActionsUiHelper.ensureSignedIn(page, gitea);

        String branch = "development";
        PromptSpec createdPrompt = waitForPromptSpec(branch, Duration.ofMinutes(2));
        assertThat(createdPrompt.getVersion()).isNotBlank().endsWith("-SNAPSHOT");
    }

    @Test
    @Order(25)
    @DisplayName("Run prompt from editor and persist a MANUAL execution (#140)")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void runPromptPersistsManualExecution() {
        // Reach the editor for the prompt created in @Order(20). The catalog row
        // opens the read view; "Edit" navigates to /prompts/:id/edit.
        navigateToApplication();
        navigateToPath("/prompts");
        page.getByTestId("prompt-card-%s-action".formatted(PROMPT_NAME)).click();
        page.getByTestId("prompt-edit-action").click();
        page.getByTestId("prompt-editor-heading").waitFor();

        // Click Run and wait for the execute response so we know the panel has
        // had a chance to update. This issues a real LLM call against the
        // OPENAI_API_KEY configured in the environment (local shell / CI
        // secret), so the test fails fast if no key is wired up.
        page.waitForResponse(
                response -> response.url().contains("/execute")
                        && "POST".equalsIgnoreCase(response.request().method()),
                () -> page.getByTestId("prompt-editor-run-action").click());

        // UI surface: the run response container should render with the LLM
        // response. We assert non-empty rather than any specific content so
        // the test stays robust against the model's nondeterministic output.
        Locator runResponse = page.getByTestId("prompt-editor-run-response");
        runResponse.waitFor();
        assertThat(runResponse.textContent()).isNotBlank();

        // YAML surface: the dev run should append a MANUAL Execution with the
        // response content. Refetch the development-branch YAML and assert.
        PromptSpec afterRun = waitForPromptSpec("development", Duration.ofMinutes(2));
        assertThat(afterRun.getExecutions())
                .as("dev-run must append at least one Execution to spec.executions[]")
                .isNotNull()
                .isNotEmpty();
        Execution latest = afterRun.getExecutions().get(0);
        assertThat(latest.kindOrManual()).isEqualTo(ExecutionKind.MANUAL);
    }

    @Test
    @Order(30)
    @DisplayName("release prompt")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void releasePrompt() {
        PromptSpec beforeRelease = waitForPromptSpec("development", Duration.ofMinutes(2));
        String developmentVersionBeforeRelease = beforeRelease.getVersion();
        String expectedReleaseVersion = normalizeReleaseVersion(developmentVersionBeforeRelease);
        String expectedNextDevelopmentVersion = nextDevelopmentVersion(expectedReleaseVersion);

        navigateToApplication();
        navigateToPath("/prompts");
        boolean visible = page.waitForSelector("text=" + developmentVersionBeforeRelease).isVisible();
        assertThat(visible).isTrue();

        // Keep workflow selection deterministic: clear stale runs before triggering the release.
        ensureRepositoryVariablesConfigured();
        gitea.resetRepositoryActionsState(repositoryOwner, REPO_NAME);
        gitea.logRepositoryActionsDiagnostics(repositoryOwner, REPO_NAME);

        // v2 UX: clicking a catalog row opens the read view; the editor lives at
        // /prompts/:id/edit. Click "Edit" to reach the editor, then Release.
        page.getByTestId("prompt-card-%s-action".formatted(PROMPT_NAME)).click();
        page.getByTestId("prompt-edit-action").click();

        // The v2 edit-mode submit fires save (PUT) and THEN release (POST) in
        // a single async handler. If we navigate away (hard nav via
        // page.navigate) before the closure reaches the release POST, the
        // browser tears down JS and the release never fires. Wait for the
        // release HTTP response first.
        page.waitForResponse(
                response -> response.url().contains("/release")
                        && "POST".equalsIgnoreCase(response.request().method()),
                () -> page.getByTestId("prompt-editor-release-action").click());
        navigateToPath("/prompts");
        takeScreenshot("here.png");
        verifyPromptVersion("development", expectedNextDevelopmentVersion);

        // Verify deployed prompt in Gitea
        verifyPromptVersion("main", expectedReleaseVersion);

        // Per #140: the pre-release-execute gate runs the spec server-side before
        // promotion and appends a PRE_RELEASE Execution to spec.executions[]. The
        // released YAML on main must carry that record with the LLM response.
        PromptSpec releasedSpec = waitForPromptSpec("main", expectedReleaseVersion, Duration.ofMinutes(2));
        assertThat(releasedSpec.getExecutions())
                .as("released YAML must contain executions[] from the pre-release-execute gate")
                .isNotNull()
                .isNotEmpty();
        assertThat(releasedSpec.getExecutions())
                .anyMatch(execution -> execution.kindOrManual() == ExecutionKind.PRE_RELEASE
                        && execution.getResponse() instanceof ChatCompletionResponse chat
                        && chat.getContent() != null
                        && !chat.getContent().isBlank());
    }

    @Test
    @Order(40)
    @DisplayName("Release Project")
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void releaseProject() {
        navigateToGitea();
        waitUntilBuildSucceeded();
        JsonNode deployments = waitForArtifactoryDeployments();
        log.info("Artifactory deployments: {}", deployments);

        JsonNode children = deployments.path("children");
        assertThat(children.isArray())
                .as("Artifactory repository listing should provide children array")
                .isTrue();
        assertThat(children.size())
                .as("At least one artifact should be present in Artifactory")
                .isGreaterThan(0);

        ReleaseArtifactContractDelegate.assertPublishedReleaseArtifactContract(HTTP_CLIENT, artifactoryContainer);
    }

    private JsonNode waitForArtifactoryDeployments() {
        Duration timeout = Duration.ofMinutes(12);
        Duration pollInterval = Duration.ofSeconds(5);
        AtomicReference<JsonNode> lastDeployments = new AtomicReference<>();
        AtomicReference<JsonNode> lastArchiveMetadata = new AtomicReference<>();
        AtomicReference<RuntimeException> lastFetchError = new AtomicReference<>();

        PollingHelper.Result<JsonNode> result = PollingHelper.pollUntil(
                timeout,
                pollInterval,
                () -> {
                    try {
                        JsonNode deployments = ArtifactoryStorageHelper.fetchArtifactoryDeployments(HTTP_CLIENT, artifactoryContainer);
                        lastDeployments.set(deployments);
                        lastFetchError.set(null);
                        JsonNode children = deployments.path("children");
                        if (children.isArray() && children.size() > 0) {
                            JsonNode archiveMetadata = ArtifactoryStorageHelper.findFirstArtifactArchiveMetadata(HTTP_CLIENT, artifactoryContainer);
                            lastArchiveMetadata.set(archiveMetadata);
                            if (archiveMetadata != null) {
                                String archivePath = archiveMetadata.path("path").asText();
                                log.info("Artifactory archive ready: {}", archivePath);
                                return Optional.of(deployments);
                            }
                            log.info("Artifactory has entries but no archive artifact yet; retrying in {}s",
                                    pollInterval.toSeconds());
                        } else {
                            log.info("Artifactory has no artifacts yet; retrying in {}s", pollInterval.toSeconds());
                        }
                        return Optional.empty();
                    } catch (RuntimeException fetchError) {
                        lastFetchError.set(fetchError);
                        log.warn("Failed to fetch Artifactory deployments (will retry in {}s): {}",
                                pollInterval.toSeconds(),
                                fetchError.getMessage());
                        return Optional.empty();
                    }
                });

        if (result.value().isPresent()) {
            return result.value().get();
        }
        if (lastDeployments.get() != null) {
            if (lastArchiveMetadata.get() == null) {
                throw new IllegalStateException("Timed out waiting for an archive artifact (.jar/.zip) in Artifactory");
            }
            return lastDeployments.get();
        }
        if (lastFetchError.get() != null) {
            throw lastFetchError.get();
        }
        throw new IllegalStateException("Timed out waiting for Artifactory deployments to become available");
    }

    private String buildDownloadUrl(String artifactPath) {
        String repositoryBase = artifactoryContainer.getMavenRepositoryUrl();
        String normalizedPath = artifactPath.startsWith("/") ? artifactPath.substring(1) : artifactPath;
        if (normalizedPath.isBlank()) {
            return repositoryBase;
        }
        return repositoryBase + "/" + normalizedPath;
    }

    private void navigateToGitea() {
        page.navigate(gitea.getWebUrl());
    }

    private void configureCustomPlaceholderDelimiters(String openSequence, String closeSequence) {
        // v2 rail surfaces the start/end inputs directly — no popover.
        Locator openInput = page.getByTestId("placeholder-open-sequence-input");
        Locator closeInput = page.getByTestId("placeholder-close-sequence-input");
        openInput.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        closeInput.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        openInput.fill(openSequence);
        closeInput.fill(closeSequence);
    }

    private void addPlaceholder(String name) {
        // v2 RailPlaceholders adds an empty row on '+ Add'; we then fill the name input
        // of the freshly added (last) row.
        Locator addButton = page.getByTestId("placeholder-add-button");
        addButton.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        addButton.click();
        Locator nameInput = page.locator("[data-testid^='placeholder-name-input-']").last();
        nameInput.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        nameInput.fill(name);
        // Move focus off the name field so React commits the row's name into state
        // and the row's test id (placeholder-row-${name}) becomes resolvable.
        nameInput.press("Tab");
    }

    private void setPlaceholderValue(String placeholderToken, String value) {
        Matcher placeholderNameMatcher = Pattern.compile("\\w+").matcher(placeholderToken);
        assertThat(placeholderNameMatcher.find()).isTrue();
        String placeholderName = placeholderNameMatcher.group();

        // In v2 the placeholder default value is the row's description input — the
        // shell maps `description` into `defaults` on persist.
        Locator valueEditor = page.getByTestId("placeholder-value-textarea-" + placeholderName + "-0");
        valueEditor.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        valueEditor.fill(value);
        valueEditor.press("Tab");
    }

    private void insertPlaceholderToken(String placeholderName) {
        // v2 placeholders rail does not expose click-to-insert tokens — the test
        // types the literal placeholder spelled with the configured delimiters.
        Locator promptTextarea = page.getByTestId("prompt-messages").locator("textarea").last();
        promptTextarea.click();
        page.keyboard().press("End");
        page.keyboard().type("[[" + placeholderName + "]]");
    }

    // TODO: do only once
    private void configureRepositoryVariables() {
        if (artifactoryVariablesConfigured) {
            return;
        }

        gitea.waitForRepository(REPO_NAME);
        gitea.enableRepositoryActions(repositoryOwner, REPO_NAME);

        String artifactoryRunnerUrl = System.getProperty(
                "artifactory.runner.api.url",
                System.getProperty("artifactory.internal.api.url", artifactoryContainer.getRunnerAccessibleApiUrl()));

        gitea.ensureRepositoryActionsVariable(repositoryOwner, REPO_NAME, "ARTIFACTORY_URL", artifactoryRunnerUrl);
        gitea.ensureRepositoryActionsVariable(repositoryOwner, REPO_NAME, "ARTIFACTORY_REPOSITORY", artifactoryContainer.getMavenRepositoryName());
        gitea.ensureRepositoryActionsVariable(repositoryOwner, REPO_NAME, "ARTIFACTORY_USERNAME", artifactoryContainer.getDeployerUsername());
        gitea.ensureRepositoryActionsVariable(repositoryOwner, REPO_NAME, "ARTIFACTORY_PASSWORD", artifactoryContainer.getDeployerPassword());

        artifactoryVariablesConfigured = true;
    }

    /**
     * Waits until the build is finished and the job status is "Success".
     * This method navigates to the job page and waits for the job status to be "Success".
     * The timeout for the wait is 5 minutes.
     */
    private void waitUntilBuildSucceeded() {
        String workflowFile = System.getProperty("promptlm.gitea.actions.workflow.file", "deploy-artifactory.yml");
        try {
            int removedCiTasks = gitea.cleanupActionsTaskContainers("workflow-prompt-repository-ci");
            if (removedCiTasks > 0) {
                log.info("Removed {} stale CI Actions task container(s) before deploy wait.", removedCiTasks);
            }
            gitea.logRepositoryActionsDiagnostics(repositoryOwner, REPO_NAME);

            Duration timeout = Duration.ofMinutes(12);
            Duration pollInterval = Duration.ofSeconds(2);
            gitea.waitForRepositoryActionsRun(repositoryOwner, REPO_NAME, timeout, pollInterval);

            // Keep UI navigation as a debugging aid after the API confirms completion.
            GiteaActionsUiHelper.openJobPageForWorkflow(page, gitea, repositoryOwner, REPO_NAME, workflowFile);
            log.info("Workflow '{}' finished successfully. Proceeding with Artifactory verification.", workflowFile);
        } catch (AssertionError | RuntimeException exception) {
            gitea.logRepositoryActionsDiagnostics(repositoryOwner, REPO_NAME);
            gitea.logActionsRunnerDiagnostics("release workflow '" + workflowFile + "' failed");
            throw exception;
        }
    }

    private void ensureRepositoryVariablesConfigured() {
        if (!artifactoryVariablesConfigured) {
            configureRepositoryVariables();
        }
    }

    private void verifyProjectExists() {
        assertThat(getUserHome().resolve(REPO_NAME)).exists();
        assertThat(getUserHome().resolve(".promptlm/context.json").toFile()).isNotEmpty();
    }

    private static Path getUserHome() {
        return userHome;
    }

    private void navigateToApplication() {
        playwrightSession.navigateToApp();
    }

    /**
     * Get the base URL for the application
     */
    protected String getBaseUrl() {
        return TestApplicationManager.getBaseUrl();
    }

    private void verifyPromptVersion(String branch, String expectedVersion) {
        PromptSpec promptSpec = waitForPromptSpec(branch, expectedVersion, Duration.ofMinutes(2));
        assertThat(promptSpec.getVersion()).isEqualTo(expectedVersion);
    }

    private PromptSpec waitForPromptSpec(String branch, Duration timeout) {
        return waitForPromptSpec(branch, null, timeout);
    }

    private PromptSpec waitForPromptSpec(String branch, String expectedVersion, Duration timeout) {
        AtomicReference<String> lastSeenVersion = new AtomicReference<>();

        PollingHelper.Result<PromptSpec> result = PollingHelper.pollUntil(
                timeout,
                Duration.ofSeconds(1),
                () -> {
                    Optional<String> yaml = GiteaRepositoryHelper.fetchRawFile(
                            HTTP_CLIENT,
                            getGiteaRepoUrl(),
                            branch,
                            "prompts/" + GROUP + "/" + PROMPT_NAME + "/promptlm.yml",
                            gitea.getAdminToken());
                    if (yaml.isEmpty()) {
                        return Optional.empty();
                    }

                    PromptSpec promptSpec = getPromptSpec(yaml.get());
                    lastSeenVersion.set(promptSpec.getVersion());
                    if (expectedVersion == null || expectedVersion.equals(promptSpec.getVersion())) {
                        return Optional.of(promptSpec);
                    }
                    log.debug("Prompt spec on branch '{}' currently at version '{}', waiting for '{}'", branch, promptSpec.getVersion(), expectedVersion);
                    return Optional.empty();
                });

        if (result.value().isPresent()) {
            return result.value().get();
        }

        String message = "Timed out waiting for prompt spec on branch '" + branch + "'";
        if (expectedVersion != null) {
            message = message + " with version '" + expectedVersion + "'";
        }
        if (lastSeenVersion.get() != null) {
            message = message + " (last seen version '" + lastSeenVersion.get() + "')";
        }
        IllegalStateException timeoutException = new IllegalStateException(message);
        result.lastError().ifPresent(timeoutException::initCause);
        throw timeoutException;
    }

    private static PromptSpec getPromptSpec(String rawYaml) {
        try {
            return ObjectMapperFactory.createYamlMapper().readValue(rawYaml, PromptSpec.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to parse prompt spec YAML", e);
        }
    }

    private static @NotNull String getGiteaRepoUrl() {
        return gitea.getWebUrl() + "/" + gitea.getAdminUsername() + "/" + REPO_NAME;
    }

    private void takeScreenshot(String fileName) {
        try {
            Path screenshotsDir = Paths.get("target/screenshots");
            Files.createDirectories(screenshotsDir);
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshotsDir.resolve(fileName)));
        } catch (Exception e) {
            log.error("Failed to take screenshot: " + e.getMessage());
        }
    }

    private void captureFailureScreenshot(String testDisplayName) {
        if (page == null) {
            log.warn("Skipping screenshot for '{}' because Playwright page is null", testDisplayName);
            return;
        }

        String sanitizedName = SCREENSHOT_FILE_SANITIZER.matcher(testDisplayName).replaceAll("_");
        String fileName = String.format("failure-%s-%d.png", sanitizedName, System.currentTimeMillis());
        takeScreenshot(fileName);
        log.info("Stored failure screenshot for '{}' at target/screenshots/{}", testDisplayName, fileName);
    }


    private void captureFailureTrace(String testDisplayName) {
        if (page == null) {
            return;
        }
        try {
            Path tracesDir = Paths.get("target/traces");
            Files.createDirectories(tracesDir);
            String sanitizedName = SCREENSHOT_FILE_SANITIZER.matcher(testDisplayName).replaceAll("_");
            String fileName = String.format("trace-%s-%d.zip", sanitizedName, System.currentTimeMillis());
            page.context().tracing().stop(new com.microsoft.playwright.Tracing.StopOptions()
                    .setPath(tracesDir.resolve(fileName)));
            page.context().tracing().start(new com.microsoft.playwright.Tracing.StartOptions()
                    .setScreenshots(true)
                    .setSnapshots(true)
                    .setSources(true));
            log.info("Stored failure trace for '{}' at target/traces/{}", testDisplayName, fileName);
        } catch (Exception e) {
            log.warn("Failed to capture Playwright trace for '{}'", testDisplayName, e);
        }
    }

    private void handleTestFailure(String displayName, Throwable cause) {
        if (cause != null) {
            log.error("Test '{}' failed", displayName, cause);
        } else {
            log.error("Test '{}' failed", displayName);
        }
        captureFailureScreenshot(displayName);
        captureFailureTrace(displayName);
    }

    private static final Pattern SCREENSHOT_FILE_SANITIZER = Pattern.compile("[^a-zA-Z0-9-_]+");
    private static final Pattern INTEGER_VERSION_PATTERN = Pattern.compile("^(\\d+)$");
    private static final Pattern SEMVER_VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

    private static String normalizeReleaseVersion(String version) {
        if (version == null || version.isBlank()) {
            return "1";
        }
        if (version.endsWith("-SNAPSHOT")) {
            return version.substring(0, version.length() - "-SNAPSHOT".length());
        }
        return version;
    }

    private static String nextDevelopmentVersion(String releaseVersion) {
        Matcher integerMatcher = INTEGER_VERSION_PATTERN.matcher(releaseVersion);
        if (integerMatcher.matches()) {
            long current = Long.parseLong(integerMatcher.group(1));
            return (current + 1) + "-SNAPSHOT";
        }

        Matcher semverMatcher = SEMVER_VERSION_PATTERN.matcher(releaseVersion);
        if (semverMatcher.matches()) {
            long major = Long.parseLong(semverMatcher.group(1));
            long minor = Long.parseLong(semverMatcher.group(2));
            long patch = Long.parseLong(semverMatcher.group(3));
            return major + "." + minor + "." + (patch + 1) + "-SNAPSHOT";
        }

        throw new IllegalStateException("Unsupported version format for release validation: " + releaseVersion);
    }

    /**
     * Navigate to a specific path within the application
     */
    protected void navigateToPath(String path) {

        PlaywrightNavigationHelper.navigateToPath(page, getBaseUrl(), path);
    }

    static class ScreenshotOnFailureExtension implements TestWatcher {


        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            context.getTestInstance()
                    .filter(HappyPathUserJourneyTest.class::isInstance)
                    .map(HappyPathUserJourneyTest.class::cast)
                    .ifPresent(test -> test.handleTestFailure(context.getDisplayName(), cause));
        }
    }
}
