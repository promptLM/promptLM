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
import dev.promptlm.repository.template.ArtifactCoordinateSanitizer;
import dev.promptlm.repository.template.TemplateContext;
import dev.promptlm.repository.template.TemplateSubstitutionEngine;
import dev.promptlm.test.support.ReleaseArtifactContractDelegate;
import dev.promptlm.testutils.artifactory.Artifactory;
import dev.promptlm.testutils.artifactory.ArtifactoryContainer;
import dev.promptlm.testutils.artifactory.WithArtifactory;
import dev.promptlm.testutils.gitea.Gitea;
import dev.promptlm.testutils.gitea.GiteaActions;
import dev.promptlm.testutils.gitea.GiteaContainer;
import dev.promptlm.testutils.gitea.GiteaWorkflowException;
import dev.promptlm.testutils.gitea.WithGitea;
import dev.promptlm.test.util.ZipTestUtils;
import org.assertj.core.api.WithAssertions;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import static org.assertj.core.api.Assertions.assertThat;

@WithGitea(actionsEnabled = true, createTestRepos = true, testRepoNames = {CiWorkflowHarnessTest.REPO_NAME})
@WithArtifactory
@IntegrationTest
class CiWorkflowHarnessTest implements WithAssertions {

    static final String REPO_NAME = "template-repo";
    /**
     * The 0.1.0 canonical publisher (bundle-release). Triggered via Gitea's
     * {@code workflow_dispatch} REST endpoint — {@code bundle-release.yml}
     * deliberately does not fire on {@code push}, because the org-wide
     * standard (promptlm-release {@code ci-workflow-design.md} §2) is never
     * to publish from {@code push: tags:} or push-to-{@code main}. See
     * {@code docs/release-classes.md} for the platform-release vs
     * bundle-release distinction.
     */
    private static final String WORKFLOW_FILE = "bundle-release.yml";
    /** Version dispatched to the workflow. Any semver-shaped string works. */
    private static final String DISPATCH_VERSION = "0.1.0";
    private static final Logger log = LoggerFactory.getLogger(CiWorkflowHarnessTest.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private static final String EMPTY_CONTEXT_JSON = """
            {
                \"projects\":[],
                \"activeProject\":null
            }
            """;
    @TempDir
    private Path tempDir;
    private RepositorySeeder repositorySeeder;
    private PlaywrightSession playwrightSession;
    private Page page;
    private GiteaContainer gitea;
    private ArtifactoryContainer artifactory;
    private String originalUserHome;
    private Path promptlmHome;

    @BeforeEach
    void setUpBootstrap(@Gitea GiteaContainer gitea, @Artifactory ArtifactoryContainer artifactory) {
        this.gitea = gitea;
        this.artifactory = artifactory;
        repositorySeeder = new RepositorySeeder(gitea, tempDir);
        isolatePromptlmHome();
        playwrightSession = PlaywrightSession.startPlaywrightSession(gitea.getWebUrl());
        page = playwrightSession.getPage();
    }

    @AfterEach
    void tearDownPlaywright() {
        try {
            if (playwrightSession != null) {
                try {
                    playwrightSession.shutdown();
                } finally {
                    playwrightSession = null;
                    page = null;
                }
            }
        } finally {
            if (repositorySeeder != null) {
                try {
                    repositorySeeder.resetTemplateRepository();
                } catch (Exception e) {
                    log.warn("Failed to reset template repository state", e);
                }
            }
            try {
                resetPromptlmMetadata();
            } catch (Exception e) {
                log.warn("Failed to reset promptlm metadata", e);
            } finally {
                restoreUserHome();
            }
        }
    }

    @Test
    @DisplayName("Dispatches bundle-release.yml via harness and verifies artifacts")
    void shouldExecuteCiWorkflowAndPublishArtifacts(@Gitea GiteaContainer gitea, @Artifactory ArtifactoryContainer artifactory) {
        log.info("Starting CI workflow harness test against Gitea webUrl={} apiUrl={} artifactoryUrl={}",
                gitea.getWebUrl(), gitea.getApiUrl(), artifactory.getRunnerAccessibleApiUrl());
        gitea.resetRepositoryActionsState(gitea.getAdminUsername(), REPO_NAME);
        log.info("Reset Actions state before seeding CI workflow for {}/{}", gitea.getAdminUsername(), REPO_NAME);

        configureRepositoryVariables(gitea, artifactory);
        log.info("Repository variables configured for {}/{}", gitea.getAdminUsername(), REPO_NAME);

        String seededCommitSha = repositorySeeder.seedTemplateRepository();
        log.info("Template repository seeded for {}/{} at commit {}",
                gitea.getAdminUsername(), REPO_NAME, seededCommitSha);

        // bundle-release.yml does not fire on push; explicitly dispatch it.
        dispatchBundleReleaseWorkflow(gitea, gitea.getAdminUsername(), REPO_NAME, DISPATCH_VERSION);
        log.info("Dispatched {} on {}/{} ref=main version={}",
                WORKFLOW_FILE, gitea.getAdminUsername(), REPO_NAME, DISPATCH_VERSION);

        Duration timeout = Duration.ofMinutes(12);
        Duration pollInterval = Duration.ofSeconds(5);
        GiteaActions.ActionExecutionReport workflowReport =
                waitForWorkflowExecution(gitea, gitea.getAdminUsername(), REPO_NAME, seededCommitSha, timeout, pollInterval);
        String conclusion = workflowReport.run().conclusion();
        if (conclusion != null && !"success".equalsIgnoreCase(conclusion)) {
            log.error("Workflow run terminated with conclusion={}; dumping diagnostics before assertion", conclusion);
            gitea.logRepositoryActionsDiagnostics(gitea.getAdminUsername(), REPO_NAME);
        }
        assertSuccessfulWorkflowExecution(workflowReport, seededCommitSha);

        ReleaseArtifactContractDelegate.assertPublishedReleaseArtifactContract(HTTP_CLIENT, artifactory);

        // Keep UI navigation as a debugging aid when the API says the workflow ran.
        GiteaActionsUiHelper.ensureSignedIn(page, gitea);
        GiteaActionsUiHelper.openJobPageForWorkflow(page, gitea, "testuser", REPO_NAME, WORKFLOW_FILE);
    }

    private void configureRepositoryVariables(GiteaContainer gitea, ArtifactoryContainer artifactory) {
        String owner = gitea.getAdminUsername();
        gitea.enableRepositoryActions(owner, REPO_NAME);
        // bundle-release.yml reads BUNDLE_RELEASE_* to override the shipped
        // default (maven.pkg.github.com/<owner>/<repo>, GITHUB_TOKEN auth) and
        // route the publish to the local Artifactory testcontainer playing the
        // role of a Maven registry. Real-world repos rely on the default and
        // configure none of these.
        String bundleReleaseMavenUrl = artifactory.getRunnerAccessibleApiUrl()
                + "/" + artifactory.getMavenRepositoryName();
        gitea.ensureRepositoryActionsVariable(owner, REPO_NAME,
                "BUNDLE_RELEASE_MAVEN_URL", bundleReleaseMavenUrl);
        gitea.ensureRepositoryActionsVariable(owner, REPO_NAME,
                "BUNDLE_RELEASE_USERNAME", artifactory.getDeployerUsername());
        gitea.ensureRepositoryActionsVariable(owner, REPO_NAME,
                "BUNDLE_RELEASE_PASSWORD", artifactory.getDeployerPassword());
    }

    /**
     * POST {@code /api/v1/repos/{owner}/{repo}/actions/workflows/{file}/dispatches}
     * to fire {@code bundle-release.yml} on {@code main}. Gitea Actions implements
     * the same REST contract as GitHub Actions; using the API directly avoids
     * polluting the shipped workflow with a push-to-main trigger that the org
     * standard forbids.
     */
    private void dispatchBundleReleaseWorkflow(GiteaContainer gitea,
                                                String owner,
                                                String repository,
                                                String version) {
        String dispatchUrl = gitea.getApiUrl()
                + "/repos/" + owner + "/" + repository
                + "/actions/workflows/" + WORKFLOW_FILE + "/dispatches";
        // Boolean inputs over the workflow_dispatch API are sent as strings;
        // the workflow's `if: github.event.inputs.dry-run == 'true'` guard
        // compares against the string form.
        String body = """
                {"ref":"main","inputs":{"version":"%s","dry-run":"false"}}
                """.formatted(version);
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(dispatchUrl))
                .header("Authorization", "token " + gitea.getAdminToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            java.net.http.HttpResponse<String> response =
                    HTTP_CLIENT.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            // Gitea / GitHub return 204 No Content on a successful dispatch.
            if (response.statusCode() / 100 != 2) {
                gitea.logRepositoryActionsDiagnostics(owner, repository);
                throw new IllegalStateException(
                        "Failed to dispatch " + WORKFLOW_FILE + ": status=" + response.statusCode()
                                + " body=" + response.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to POST workflow dispatch", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while dispatching workflow", e);
        }
    }

    private GiteaActions.ActionExecutionReport waitForWorkflowExecution(GiteaContainer gitea,
                                                                        String owner,
                                                                        String repository,
                                                                        String commitSha,
                                                                        Duration timeout,
                                                                        Duration pollInterval) {
        try {
            return gitea.actions().waitForWorkflowRunBySha(owner, repository, commitSha, timeout, pollInterval);
        } catch (GiteaWorkflowException e) {
            gitea.logRepositoryActionsDiagnostics(owner, repository);
            throw e;
        }
    }

    private void assertSuccessfulWorkflowExecution(GiteaActions.ActionExecutionReport workflowReport,
                                                   String seededCommitSha) {
        assertThat(workflowReport.run().headSha())
                .as("workflow run should match seeded commit")
                .startsWith(seededCommitSha);
        assertThat(workflowReport.run().status())
                .as("workflow run status should be completed")
                .isEqualToIgnoringCase("completed");
        assertThat(workflowReport.run().conclusion())
                .as("workflow run conclusion should be success")
                .isEqualToIgnoringCase("success");
        assertThat(workflowReport.allJobsTerminal())
                .as("workflow jobs should be terminal")
                .isTrue();
        assertThat(workflowReport.jobs())
                .as("workflow should have at least one job")
                .isNotEmpty();
        workflowReport.jobs().forEach(job ->
                assertThat(job.conclusion() == null ? "" : job.conclusion().toLowerCase(Locale.ROOT))
                        .as("workflow job %s should be successful or skipped", job.name())
                        .isIn("success", "skipped"));
    }

    private static final class RepositorySeeder {

        private static final String DEFAULT_TEMPLATE_RESOURCE = "repo-template.zip";

        private final GiteaContainer gitea;
        private final Path workspace;
        private final Path repoDir;
        private final String templateResource;

        private RepositorySeeder(GiteaContainer gitea, Path workspace) {
            this(gitea, workspace, DEFAULT_TEMPLATE_RESOURCE);
        }

        /**
         * Hook for tests that need to seed a non-default template archive (for example a
         * Mode 2 release-enabled template once #161 splits the repository template). The
         * two-arg constructor preserves today's behaviour by loading {@code repo-template.zip}.
         */
        private RepositorySeeder(GiteaContainer gitea, Path workspace, String templateResource) {
            this.gitea = gitea;
            this.workspace = workspace;
            this.repoDir = workspace.resolve(REPO_NAME + "-local");
            this.templateResource = templateResource;
        }

        String seedTemplateRepository() {
            try {
                gitea.waitForRepository(REPO_NAME);
                Files.createDirectories(repoDir);
                initialiseRepository(repoDir);
                extractTemplate(repoDir);
                String commitSha = commitAndPush(repoDir, false);
                gitea.waitForRepository(REPO_NAME);
                return commitSha;
            } catch (IOException | GitAPIException | URISyntaxException e) {
                throw new IllegalStateException("Failed to seed repository", e);
            }
        }

        void resetTemplateRepository() {
            try {
                deleteDirectory(repoDir);
                Files.createDirectories(repoDir);
                initialiseRepository(repoDir);
                extractTemplate(repoDir);
                commitAndPush(repoDir, true);
            } catch (IOException | GitAPIException | URISyntaxException e) {
                throw new IllegalStateException("Failed to reset repository state", e);
            } finally {
                try {
                    deleteDirectory(repoDir);
                } catch (IOException e) {
                    log.warn("Failed to clean local repository at {}", repoDir, e);
                }
            }
        }

        private InputStream resourceStream() {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = RepositorySeeder.class.getClassLoader();
            }
            try {
                var resources = classLoader.getResources(templateResource);
                StringBuilder locations = new StringBuilder();
                while (resources.hasMoreElements()) {
                    var resourceUrl = resources.nextElement();
                    String location = resourceUrl.toExternalForm();
                    if (locations.length() > 0) {
                        locations.append(", ");
                    }
                    locations.append(location);
                    if (location.contains("/repository-template/") || location.contains("repository-template-")) {
                        return resourceUrl.openStream();
                    }
                }
                throw new IllegalStateException(templateResource + " from repository-template module not found. "
                        + "Discovered locations: [" + locations + "]");
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load " + templateResource + " from repository-template module", e);
            }
        }

        private void initialiseRepository(Path repoDir) throws GitAPIException, URISyntaxException {
            try (Git git = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("main").call()) {
                git.remoteAdd()
                        .setName("origin")
                        .setUri(new URIish(remoteUrl()))
                        .call();
            }
        }

        /**
         * Path patterns that must land in the seeded repo with the executable bit set —
         * mirrors {@code ZipFileRepositoryTemplateExtractor.EXECUTABLE_PATH_PATTERNS} in the
         * production extractor. The two lists must stay in sync (see issue #331 finding #5
         * for the planned shared helper). If a new shell script is added to the template,
         * register it both here and in the production extractor.
         */
        private static final List<Pattern> EXECUTABLE_PATH_PATTERNS = List.of(
                Pattern.compile("^tools/release/[^/]+$"),
                Pattern.compile("^scripts/[^/]+\\.sh$"));

        private void extractTemplate(Path repoDir) throws IOException {
            // The shipped template contains tokens like {{MAVEN_GROUP_ID}} that are
            // invalid Maven coordinates until substituted; a raw unzip leaves the seeded
            // repo with an unparseable pom.xml, the workflow dies in versions:set, and the
            // outer test waits 12 minutes for an artifact that never gets published.
            // Mirror the production extractor: substitute text entries and mark release
            // scripts executable so JGit's add+commit preserves the +x bit downstream.
            try (InputStream inputStream = resourceStream()) {
                ZipTestUtils.unzip(inputStream, repoDir);
            }
            applyTemplateSubstitutionAndPermissions(repoDir);
        }

        private void applyTemplateSubstitutionAndPermissions(Path repoDir) throws IOException {
            TemplateSubstitutionEngine engine = new TemplateSubstitutionEngine();
            TemplateContext context = buildTemplateContext();
            boolean posixSupported = FileSystems.getDefault()
                    .supportedFileAttributeViews().contains("posix");
            Set<java.nio.file.attribute.PosixFilePermission> executablePerms = posixSupported
                    ? PosixFilePermissions.fromString("rwxr-xr-x")
                    : Set.of();

            try (Stream<Path> walk = Files.walk(repoDir)) {
                List<Path> files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
                for (Path file : files) {
                    String relativeEntry = repoDir.relativize(file).toString().replace('\\', '/');
                    if (engine.isTextEntry(relativeEntry)) {
                        byte[] original = Files.readAllBytes(file);
                        byte[] substituted = engine.substitute(relativeEntry, original, context);
                        Files.write(file, substituted);
                    }
                    if (posixSupported && isExecutableEntry(relativeEntry)) {
                        try {
                            Files.setPosixFilePermissions(file, executablePerms);
                        } catch (UnsupportedOperationException ignored) {
                            // Non-POSIX filesystem — best effort only.
                        }
                    }
                }
            }
        }

        private TemplateContext buildTemplateContext() {
            String owner = gitea.getAdminUsername();
            // Use the canonical 11-arg constructor and call ArtifactCoordinateSanitizer
            // explicitly so the seeded pom.xml ends up with valid coordinates like
            // <groupId>io.github.testuser</groupId> / <artifactId>template-repo</artifactId>.
            return new TemplateContext(
                    REPO_NAME,
                    owner,
                    "Acceptance-test seeded template repository",
                    Instant.parse("2026-05-17T01:23:45Z"),
                    "acceptance-test",
                    ArtifactCoordinateSanitizer.projectName(REPO_NAME),
                    ArtifactCoordinateSanitizer.mavenGroupId(owner),
                    ArtifactCoordinateSanitizer.mavenArtifactId(REPO_NAME),
                    ArtifactCoordinateSanitizer.pythonDistributionName(REPO_NAME),
                    ArtifactCoordinateSanitizer.pythonImportName(REPO_NAME),
                    ArtifactCoordinateSanitizer.npmPackageName(REPO_NAME));
        }

        private static boolean isExecutableEntry(String entryName) {
            if (entryName == null) {
                return false;
            }
            String normalized = entryName.replace('\\', '/').toLowerCase(Locale.ROOT);
            for (Pattern p : EXECUTABLE_PATH_PATTERNS) {
                if (p.matcher(normalized).matches()) {
                    return true;
                }
            }
            return false;
        }

        private String commitAndPush(Path repoDir, boolean force) throws IOException, GitAPIException {
            try (Git git = Git.open(repoDir.toFile())) {
                git.add().addFilepattern(".").call();
                RevCommit commit = git.commit()
                        .setMessage("Seed repository template")
                        .setAuthor(gitea.getAdminUsername(), gitea.getAdminUsername() + "@example.com")
                        .setCommitter(gitea.getAdminUsername(), gitea.getAdminUsername() + "@example.com")
                        .call();
                git.push()
                        .setRemote("origin")
                        .setCredentialsProvider(credentials())
                        .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"))
                        .setForce(force)
                        .call();
                return commit.getId().getName();
            }
        }

        private void commitAndPush(Path repoDir) throws IOException, GitAPIException {
            commitAndPush(repoDir, false);
        }

        void triggerWorkflowRun() {
            try {
                Path marker = repoDir.resolve(".workflow-trigger");
                Files.writeString(
                        marker,
                        "trigger-" + System.currentTimeMillis(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );

                try (Git git = Git.open(repoDir.toFile())) {
                    git.add().addFilepattern(marker.getFileName().toString()).call();
                    git.commit()
                            .setMessage("Trigger workflow run")
                            .setAuthor(gitea.getAdminUsername(), gitea.getAdminUsername() + "@example.com")
                            .setCommitter(gitea.getAdminUsername(), gitea.getAdminUsername() + "@example.com")
                            .call();
                    git.push()
                            .setRemote("origin")
                            .setCredentialsProvider(credentials())
                            .setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main"))
                            .call();
                }
            } catch (IOException | GitAPIException e) {
                throw new IllegalStateException("Failed to push workflow trigger commit", e);
            }
        }

        private UsernamePasswordCredentialsProvider credentials() {
            return new UsernamePasswordCredentialsProvider(gitea.getAdminUsername(), gitea.getAdminToken());
        }

        private String remoteUrl() {
            return gitea.getWebUrl() + "/" + gitea.getAdminUsername() + "/" + REPO_NAME + ".git";
        }
    }

    private void isolatePromptlmHome() {
        promptlmHome = tempDir.resolve("promptlm-home");
        try {
            Files.createDirectories(promptlmHome);
            originalUserHome = System.getProperty("user.home");
            System.setProperty("user.home", promptlmHome.toString());
            resetPromptlmMetadata();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare isolated promptlm home", e);
        }
    }

    private void resetPromptlmMetadata() throws IOException {
        if (promptlmHome == null) {
            return;
        }
        Path metadataDir = promptlmHome.resolve(".promptlm");
        Files.createDirectories(metadataDir);
        Path contextFile = metadataDir.resolve("context.json");
        Files.writeString(contextFile, EMPTY_CONTEXT_JSON, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void restoreUserHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
            originalUserHome = null;
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (directory == null || Files.notExists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }
}
