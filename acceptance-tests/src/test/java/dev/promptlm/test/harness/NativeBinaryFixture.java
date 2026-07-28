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

package dev.promptlm.test.harness;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.promptlm.test.support.LlmStubServer;
import dev.promptlm.test.support.NativeBinaryLauncher;
import dev.promptlm.testutils.artifactory.ArtifactoryContainer;
import dev.promptlm.testutils.gitea.GiteaContainer;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * JUnit 5 extension that boots the promptLM native binaries plus their
 * optional infrastructure (Gitea / Artifactory / LLM stub) for a single
 * test class.
 *
 * <p>Lifecycle:
 * <ul>
 *     <li>{@link #evaluateExecutionCondition(ExtensionContext)} — skips the
 *         class when a required binary is missing, unless
 *         {@code -Dpromptlm.test.requireNativeBinaries=true} promotes the
 *         skip to a hard failure.</li>
 *     <li>{@link #beforeAll(ExtensionContext)} — provisions the user-home /
 *         workspace skeleton, starts requested containers, starts the LLM
 *         stub if asked, and starts the native webapp / CLI binaries.</li>
 *     <li>{@link #beforeEach(ExtensionContext)} — creates a fresh per-test
 *         sub-workspace inside the class-level user-home, so test cases
 *         don't pollute each other.</li>
 *     <li>{@link #afterAll(ExtensionContext)} — tears down processes,
 *         Playwright, containers, and the LLM stub independently so a
 *         failure in one doesn't strand the others (mirrors the pattern in
 *         {@code NativeWebappUiSmokeTest.afterAll}).</li>
 * </ul>
 *
 * <p>The extension exposes {@link NativeAppHandle} (and its subtypes) as
 * method-level parameters via {@link ParameterResolver}.
 */
public final class NativeBinaryFixture implements
        BeforeAllCallback,
        AfterAllCallback,
        BeforeEachCallback,
        ParameterResolver,
        ExecutionCondition {

    public static final String REQUIRE_BINARIES_PROPERTY = "promptlm.test.requireNativeBinaries";

    private static final Logger log = LoggerFactory.getLogger(NativeBinaryFixture.class);
    private static final Namespace NAMESPACE = Namespace.create(NativeBinaryFixture.class);
    private static final String STATE_KEY = "state";
    private static final Duration BACKEND_READY_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration BINARY_STOP_TIMEOUT = Duration.ofSeconds(15);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // -----------------------------------------------------------------
    // ExecutionCondition
    // -----------------------------------------------------------------

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<WithNativeApp> declared = findAnnotation(context);
        if (declared.isEmpty()) {
            return ConditionEvaluationResult.enabled("No @WithNativeApp on class");
        }
        WithNativeApp annotation = declared.get();
        boolean hardRequire = Boolean.parseBoolean(System.getProperty(REQUIRE_BINARIES_PROPERTY, "false"));
        for (WithNativeApp.Binary binary : annotation.binaries()) {
            String missing = checkBinaryAvailable(binary);
            if (missing != null) {
                String message = "Native binary unavailable for " + binary + ": " + missing
                        + " — build the binaries with build-full.sh (--full / --native) before running.";
                if (hardRequire) {
                    throw new IllegalStateException(message);
                }
                return ConditionEvaluationResult.disabled(message);
            }
        }
        return ConditionEvaluationResult.enabled("Native binaries available");
    }

    // -----------------------------------------------------------------
    // BeforeAll: bring up infra + binaries.
    // -----------------------------------------------------------------

    @Override
    public void beforeAll(ExtensionContext context) throws IOException {
        WithNativeApp annotation = requireAnnotation(context);

        Path classRoot = resolveClassRoot(context);
        Files.createDirectories(classRoot);

        FixtureState state = new FixtureState(annotation, classRoot);

        if (annotation.withGitea()) {
            state.gitea = startGitea();
        }
        if (annotation.withArtifactory()) {
            state.artifactory = startArtifactory();
        }
        if (annotation.withLlmStub()) {
            state.llmStub = LlmStubServer.start();
        }

        Path userHome = classRoot.resolve("home");
        Path workspace = userHome.resolve("workspace");
        Files.createDirectories(workspace);
        state.userHome = userHome;
        state.workspace = workspace;
        state.sysProps = buildSystemProperties(state);

        boolean needsWebapp = false;
        boolean needsCli = false;
        for (WithNativeApp.Binary binary : annotation.binaries()) {
            if (binary == WithNativeApp.Binary.WEBAPP) {
                needsWebapp = true;
            }
            if (binary == WithNativeApp.Binary.CLI) {
                needsCli = true;
            }
        }
        if (needsWebapp) {
            startWebapp(state);
        }
        if (needsCli) {
            state.cliHandle = new NativeAppHandle.CliHandle(
                    NativeBinaryLauncher.resolveRequiredCliBinaryPath(),
                    state.userHome,
                    state.workspace,
                    Map.copyOf(state.sysProps),
                    Duration.ofMinutes(3));
        }

        getStore(context).put(STATE_KEY, state);
    }

    // -----------------------------------------------------------------
    // BeforeEach: fresh per-test sub-workspace.
    // -----------------------------------------------------------------

    @Override
    public void beforeEach(ExtensionContext context) throws IOException {
        FixtureState state = getRequiredState(context);
        String testTag = context.getRequiredTestMethod().getName() + "-" + UUID.randomUUID();
        Path perTest = state.classRoot.resolve("cases").resolve(testTag);
        Files.createDirectories(perTest);
        state.perTestRoot = perTest;
    }

    // -----------------------------------------------------------------
    // AfterAll: tear down independently.
    // -----------------------------------------------------------------

    @Override
    public void afterAll(ExtensionContext context) {
        FixtureState state = getStore(context).remove(STATE_KEY, FixtureState.class);
        if (state == null) {
            return;
        }
        // Page / browser first.
        closeQuietly(state.page);
        closeQuietly(state.browser);
        closeQuietly(state.playwright);
        if (state.webappProcess != null && state.webappProcess.process() != null) {
            stopProcess(state.webappProcess.process());
        }
        closeQuietly(state.llmStub);
        if (state.gitea != null) {
            try {
                state.gitea.stop();
            }
            catch (RuntimeException ignored) {
                // Best-effort cleanup.
            }
        }
        if (state.artifactory != null) {
            try {
                state.artifactory.stop();
            }
            catch (RuntimeException ignored) {
                // Best-effort cleanup.
            }
        }
    }

    // -----------------------------------------------------------------
    // ParameterResolver — expose the handle (and witness deps) to tests.
    // -----------------------------------------------------------------

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        return type == NativeAppHandle.class
                || type == NativeAppHandle.WebappHandle.class
                || type == NativeAppHandle.CliHandle.class
                || type == GiteaContainer.class
                || type == ArtifactoryContainer.class
                || type == LlmStubServer.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        FixtureState state = getRequiredState(extensionContext);
        Class<?> type = parameterContext.getParameter().getType();
        if (type == NativeAppHandle.WebappHandle.class) {
            return requireWebapp(state);
        }
        if (type == NativeAppHandle.CliHandle.class) {
            return requireCli(state);
        }
        if (type == NativeAppHandle.class) {
            if (state.webappHandle != null) {
                return state.webappHandle;
            }
            return requireCli(state);
        }
        if (type == GiteaContainer.class) {
            if (state.gitea == null) {
                throw new ParameterResolutionException("Gitea container not started — set @WithNativeApp(withGitea=true)");
            }
            return state.gitea;
        }
        if (type == ArtifactoryContainer.class) {
            if (state.artifactory == null) {
                throw new ParameterResolutionException("Artifactory container not started — set @WithNativeApp(withArtifactory=true)");
            }
            return state.artifactory;
        }
        if (type == LlmStubServer.class) {
            if (state.llmStub == null) {
                throw new ParameterResolutionException("LLM stub not started — set @WithNativeApp(withLlmStub=true)");
            }
            return state.llmStub;
        }
        throw new ParameterResolutionException("Unsupported parameter type: " + type);
    }

    // -----------------------------------------------------------------
    // Internal startup helpers.
    // -----------------------------------------------------------------

    private void startWebapp(FixtureState state) throws IOException {
        state.port = findFreePort();
        state.baseUrl = "http://127.0.0.1:" + state.port;
        Map<String, String> launchProps = new LinkedHashMap<>(state.sysProps);
        state.webappProcess = NativeBinaryLauncher.startWebApplication(state.userHome, state.port, launchProps);
        awaitBackendReady(state);
        state.playwright = Playwright.create();
        boolean headless = Boolean.parseBoolean(System.getProperty(
                "playwright.headless",
                System.getenv().getOrDefault("PLAYWRIGHT_HEADLESS", "true")));
        state.browser = state.playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
        state.page = state.browser.newContext().newPage();
        StudioDriver studio = new StudioDriver(state.page, state.baseUrl);
        state.webappHandle = new NativeAppHandle.WebappHandle(
                state.webappProcess.binaryPath(),
                state.userHome,
                state.port,
                state.baseUrl,
                studio,
                state.webappProcess,
                () -> {
                    stopProcess(state.webappProcess.process());
                    state.webappProcess = NativeBinaryLauncher.startWebApplication(
                            state.userHome, state.port, state.sysProps);
                    awaitBackendReady(state);
                    return state.webappHandle;
                });
    }

    private static void awaitBackendReady(FixtureState state) {
        URI healthUri = URI.create(state.baseUrl + "/api/monitor/health");
        await().atMost(BACKEND_READY_TIMEOUT).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            assertThat(state.webappProcess.process().isAlive())
                    .as("Native webapp process must remain alive during readiness probe")
                    .isTrue();
            HttpRequest request = HttpRequest.newBuilder(healthUri).GET().build();
            HttpResponse<String> response;
            try {
                response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            }
            catch (IOException ioe) {
                throw new AssertionError("Health probe IO error: " + ioe.getMessage(), ioe);
            }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Health probe interrupted", ie);
            }
            assertThat(response.statusCode()).isBetween(200, 299);
            assertThat(response.body()).contains("\"status\":\"UP\"");
        });
    }

    private static Map<String, String> buildSystemProperties(FixtureState state) {
        Map<String, String> sysProps = new LinkedHashMap<>();
        if (state.gitea != null) {
            GiteaContainer gitea = state.gitea;
            sysProps.put("gitea.url", gitea.getWebUrl());
            sysProps.put("REPO_REMOTE_URL", gitea.getWebUrl() + "/api/v1");
            sysProps.put("REPO_REMOTE_USERNAME", gitea.getAdminUsername());
            sysProps.put("REPO_REMOTE_TOKEN", gitea.getAdminToken());
            sysProps.put("promptlm.store.remote.endpoint", gitea.getWebUrl() + "/api/v1");
            sysProps.put("REPO_REMOTE_ALLOW_LOOPBACK_HOST_ALIASES", "true");
        }
        if (state.artifactory != null) {
            ArtifactoryContainer art = state.artifactory;
            sysProps.put("ARTIFACTORY_URL", art.getApiUrl());
            sysProps.put("ARTIFACTORY_USERNAME", art.getAdminUsername());
            sysProps.put("ARTIFACTORY_PASSWORD", art.getAdminPassword());
        }
        if (state.llmStub != null) {
            // Spring AI's OpenAI client reads base-url from this property.
            // Trailing slash off — Spring AI appends `/v1/chat/completions`.
            sysProps.put("spring.ai.openai.base-url", state.llmStub.baseUrl());
            sysProps.put("spring.ai.openai.api-key", "stub-key");
        }
        return sysProps;
    }

    private static GiteaContainer startGitea() {
        GiteaContainer container = new GiteaContainer();
        container.start();
        return container;
    }

    private static ArtifactoryContainer startArtifactory() {
        ArtifactoryContainer container = new ArtifactoryContainer();
        try {
            container.start();
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to start Artifactory container", e);
        }
        return container;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static String checkBinaryAvailable(WithNativeApp.Binary binary) {
        String propName = binary == WithNativeApp.Binary.CLI
                ? NativeBinaryLauncher.CLI_NATIVE_PATH_PROPERTY
                : NativeBinaryLauncher.WEBAPP_NATIVE_PATH_PROPERTY;
        String envName = propName.toUpperCase(Locale.ROOT).replace('.', '_');
        String configured = System.getProperty(propName,
                System.getenv().getOrDefault(envName, null));
        if (configured == null) {
            // Fall through to default path inside NativeBinaryLauncher.
            try {
                if (binary == WithNativeApp.Binary.CLI) {
                    NativeBinaryLauncher.resolveRequiredCliBinaryPath();
                }
                else {
                    NativeBinaryLauncher.resolveRequiredWebappBinaryPath();
                }
                return null;
            }
            catch (IllegalStateException e) {
                return e.getMessage();
            }
        }
        Path path = Path.of(configured);
        if (!Files.isRegularFile(path)) {
            return "not a regular file: " + path;
        }
        if (!Files.isExecutable(path)) {
            return "not executable: " + path;
        }
        return null;
    }

    private static Optional<WithNativeApp> findAnnotation(ExtensionContext context) {
        return context.getTestClass().map(c -> c.getAnnotation(WithNativeApp.class));
    }

    private static WithNativeApp requireAnnotation(ExtensionContext context) {
        return findAnnotation(context).orElseThrow(
                () -> new IllegalStateException("NativeBinaryFixture requires @WithNativeApp on the test class"));
    }

    private static Path resolveClassRoot(ExtensionContext context) {
        String simpleName = context.getRequiredTestClass().getSimpleName();
        Path base = Path.of("target", "native-fixture", simpleName + "-" + UUID.randomUUID());
        return base.toAbsolutePath().normalize();
    }

    private static Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE);
    }

    private static FixtureState getRequiredState(ExtensionContext context) {
        FixtureState state = getStore(context).get(STATE_KEY, FixtureState.class);
        if (state == null) {
            throw new IllegalStateException("NativeBinaryFixture not initialized — @WithNativeApp not declared?");
        }
        return state;
    }

    private static NativeAppHandle.WebappHandle requireWebapp(FixtureState state) {
        if (state.webappHandle == null) {
            throw new ParameterResolutionException(
                    "Webapp binary not started — declare @WithNativeApp(binaries={WEBAPP, ...})");
        }
        return state.webappHandle;
    }

    private static NativeAppHandle.CliHandle requireCli(FixtureState state) {
        if (state.cliHandle == null) {
            throw new ParameterResolutionException(
                    "CLI binary not started — declare @WithNativeApp(binaries={CLI, ...})");
        }
        return state.cliHandle;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        }
        catch (Exception ignored) {
            // Best-effort cleanup.
        }
    }

    private static void stopProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(BINARY_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(BINARY_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /**
     * Mutable per-class state held by the extension store. Package-private
     * so the inner Restart lambda can read/write while remaining in this
     * compilation unit.
     */
    private static final class FixtureState {
        final WithNativeApp annotation;
        final Path classRoot;
        Path perTestRoot;
        Path userHome;
        Path workspace;
        Map<String, String> sysProps;

        GiteaContainer gitea;
        ArtifactoryContainer artifactory;
        LlmStubServer llmStub;

        NativeBinaryLauncher.RunningProcess webappProcess;
        int port;
        String baseUrl;
        Playwright playwright;
        Browser browser;
        Page page;

        NativeAppHandle.WebappHandle webappHandle;
        NativeAppHandle.CliHandle cliHandle;

        FixtureState(WithNativeApp annotation, Path classRoot) {
            this.annotation = annotation;
            this.classRoot = classRoot;
        }
    }
}
