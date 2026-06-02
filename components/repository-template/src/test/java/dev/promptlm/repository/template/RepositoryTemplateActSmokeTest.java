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

package dev.promptlm.repository.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RepositoryTemplateActSmokeTest {

    private static final String ACT_VERSION = "v0.2.68";
    private static final String ACT_PLATFORM_IMAGE = "maven:3.9.9-eclipse-temurin-21";
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(8);

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void repositoryTemplateBuildWorkflowRunsUnderAct() throws Exception {
        assumeTrue(isDockerDaemonAvailable(), "Docker daemon must be available for the act smoke test");

        Path tempDir = Files.createTempDirectory("repository-template-act-");
        try {
            Path repositoryDir = tempDir.resolve("template-repo").toAbsolutePath();
            Files.createDirectories(repositoryDir);
            extractRepositoryTemplate(repositoryDir);

            initializeGitRepository(repositoryDir, tempDir);
            Path bareRemoteRepository = createBareRemote(repositoryDir, tempDir);
            configureOriginRemote(repositoryDir, bareRemoteRepository, tempDir);
            pushMainBranch(repositoryDir, bareRemoteRepository, tempDir);

            Path eventFile = tempDir.resolve("push-event.json");
            Files.writeString(eventFile, "{\"ref\":\"refs/heads/main\"}\n", StandardCharsets.UTF_8);

            Path actExecutable = resolveActExecutable();
            Map<String, String> actEnvironment = resolveActEnvironment(tempDir);
            Path actLog = tempDir.resolve("act-smoke.log");
            CommandResult result = runCommand(
                    List.of(
                            actExecutable.toString(),
                            "push",
                            "-b",
                            "-C", repositoryDir.toString(),
                            "-e", eventFile.toString(),
                            "-W", ".github/workflows/deploy-artifactory.yml",
                            "-j", "build",
                            "-P", "ubuntu-latest=" + ACT_PLATFORM_IMAGE,
                            "--var", "REPO_REMOTE_URL=file://" + bareRemoteRepository.toAbsolutePath(),
                            "--var", "REPO_REMOTE_USERNAME=testuser",
                            "--var", "REPO_REMOTE_TOKEN=dummy-token"
                    ),
                    repositoryDir,
                    actLog,
                    COMMAND_TIMEOUT,
                    actEnvironment);

            assertThat(result.exitCode())
                    .withFailMessage("act smoke test failed. Full log:%n%s", result.output())
                    .isZero();
            assertThat(result.output()).contains("Job succeeded");

            try (var files = Files.list(repositoryDir)) {
                assertThat(files.map(path -> path.getFileName().toString()))
                        .anyMatch(name -> name.startsWith("prompts-") && name.endsWith(".jar"));
            }
        } finally {
            deleteDirectoryQuietly(tempDir);
        }
    }

    private static boolean isDockerDaemonAvailable() throws Exception {
        return commandSucceeds(List.of("docker", "info"), Duration.ofSeconds(20));
    }

    private static Map<String, String> resolveActEnvironment(Path tempDir) throws Exception {
        Map<String, String> environment = new LinkedHashMap<>();
        String dockerHost = readNonBlank(System.getenv("DOCKER_HOST"));
        if (dockerHost == null) {
            dockerHost = readNonBlank(detectDockerHostFromCurrentContext(tempDir));
        }
        if (dockerHost != null) {
            environment.put("DOCKER_HOST", dockerHost);
        }

        String dockerContext = readNonBlank(System.getenv("DOCKER_CONTEXT"));
        if (dockerContext != null) {
            environment.put("DOCKER_CONTEXT", dockerContext);
        }
        return environment;
    }

    private static String detectDockerHostFromCurrentContext(Path tempDir) throws Exception {
        CommandResult contextResult = runCommand(
                List.of("docker", "context", "show"),
                Path.of("."),
                tempDir.resolve("docker-context-show.log"),
                Duration.ofSeconds(20));
        if (contextResult.exitCode() != 0) {
            return null;
        }
        String contextName = readNonBlank(contextResult.output());
        if (contextName == null) {
            return null;
        }

        CommandResult inspectResult = runCommand(
                List.of("docker", "context", "inspect", contextName, "--format", "{{.Endpoints.docker.Host}}"),
                Path.of("."),
                tempDir.resolve("docker-context-inspect.log"),
                Duration.ofSeconds(20));
        if (inspectResult.exitCode() != 0) {
            return null;
        }
        return readNonBlank(inspectResult.output());
    }

    private static String readNonBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Path resolveActExecutable() throws Exception {
        if (commandSucceeds(List.of("act", "--version"), Duration.ofSeconds(10))) {
            return Path.of("act");
        }

        String assetName = resolveActAssetName();
        Path cacheDir = Path.of(System.getProperty("user.home"), ".cache", "prompteval", "act", ACT_VERSION);
        Path actExecutable = cacheDir.resolve("act");
        if (Files.isExecutable(actExecutable)) {
            return actExecutable;
        }

        Files.createDirectories(cacheDir);
        Path archive = cacheDir.resolve(assetName);
        if (!Files.exists(archive)) {
            downloadActArchive(assetName, archive);
        }

        runCommand(
                List.of("tar", "-xzf", archive.toString(), "-C", cacheDir.toString()),
                cacheDir,
                cacheDir.resolve("act-extract.log"),
                Duration.ofMinutes(2));

        if (!Files.isRegularFile(actExecutable)) {
            throw new IllegalStateException("Downloaded act archive did not contain an act binary: " + archive);
        }
        actExecutable.toFile().setExecutable(true);
        return actExecutable;
    }

    private static String resolveActAssetName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

        if (os.contains("mac")) {
            if (arch.equals("aarch64") || arch.equals("arm64")) {
                return "act_Darwin_arm64.tar.gz";
            }
            if (arch.equals("x86_64") || arch.equals("amd64")) {
                return "act_Darwin_x86_64.tar.gz";
            }
        }
        if (os.contains("linux")) {
            if (arch.equals("aarch64") || arch.equals("arm64")) {
                return "act_Linux_arm64.tar.gz";
            }
            if (arch.equals("x86_64") || arch.equals("amd64")) {
                return "act_Linux_x86_64.tar.gz";
            }
        }
        throw new IllegalStateException("Unsupported platform for act smoke test: os=" + os + ", arch=" + arch);
    }

    private static void downloadActArchive(String assetName, Path archive) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        URI assetUri = URI.create("https://github.com/nektos/act/releases/download/"
                + ACT_VERSION + "/" + assetName);
        HttpRequest request = HttpRequest.newBuilder(assetUri).GET().build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(archive));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Failed to download act from " + assetUri
                    + " (HTTP " + response.statusCode() + ")");
        }
    }

    private static void extractRepositoryTemplate(Path repositoryDir) throws IOException {
        // Mirror the production extractor's behavior: substitute template tokens
        // (otherwise the new {{MAVEN_GROUP_ID}} / {{MAVEN_ARTIFACT_ID}} tokens leave a
        // pom.xml that Maven refuses to parse) and apply +x to the release scripts.
        TemplateSubstitutionEngine engine = new TemplateSubstitutionEngine();
        TemplateContext context = new TemplateContext(
                "template-repo",
                "promptlm-act-smoke",
                "act-smoke template repository",
                Instant.parse("2026-05-17T01:23:45Z"),
                "smoke");

        try (InputStream resource = RepositoryTemplateActSmokeTest.class.getClassLoader()
                .getResourceAsStream("repo-template.zip")) {
            if (resource == null) {
                throw new IllegalStateException("repo-template.zip must be packaged in test resources");
            }
            try (ZipInputStream zipInputStream = new ZipInputStream(resource)) {
                ZipEntry entry;
                while ((entry = zipInputStream.getNextEntry()) != null) {
                    Path target = repositoryDir.resolve(entry.getName());
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        byte[] bytes = zipInputStream.readAllBytes();
                        if (engine.isTextEntry(entry.getName())) {
                            bytes = engine.substitute(entry.getName(), bytes, context);
                        }
                        Files.write(target, bytes);
                        // Mark release scripts executable so the workflow can invoke them.
                        String normalized = entry.getName().replace('\\', '/');
                        if (normalized.startsWith("tools/release/")
                                || (normalized.startsWith("scripts/") && normalized.endsWith(".sh"))) {
                            try {
                                Files.setPosixFilePermissions(target, Set.copyOf(
                                        java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")));
                            } catch (UnsupportedOperationException ignored) {
                                // Non-POSIX filesystem — best effort only.
                            }
                        }
                    }
                }
            }
        }
    }

    private static void initializeGitRepository(Path repositoryDir, Path tempDir) throws Exception {
        runCommandOrThrow(List.of("git", "init", "-b", "main"), repositoryDir, tempDir.resolve("git-init.log"), Duration.ofSeconds(30));
        runCommandOrThrow(List.of("git", "config", "user.name", "Codex"), repositoryDir, tempDir.resolve("git-user-name.log"), Duration.ofSeconds(30));
        runCommandOrThrow(List.of("git", "config", "user.email", "codex@example.com"), repositoryDir, tempDir.resolve("git-user-email.log"), Duration.ofSeconds(30));
        runCommandOrThrow(List.of("git", "add", "."), repositoryDir, tempDir.resolve("git-add.log"), Duration.ofSeconds(30));
        runCommandOrThrow(List.of("git", "commit", "-m", "Initial template state"), repositoryDir, tempDir.resolve("git-commit.log"), Duration.ofSeconds(30));
    }

    private static Path createBareRemote(Path repositoryDir, Path tempDir) throws Exception {
        Path bareRemote = repositoryDir.resolve(".act-remote.git").toAbsolutePath();
        runCommandOrThrow(List.of("git", "init", "--bare", bareRemote.toString()),
                repositoryDir,
                tempDir.resolve("git-init-bare.log"),
                Duration.ofSeconds(30));
        return bareRemote;
    }

    private static void configureOriginRemote(Path repositoryDir, Path bareRemote, Path tempDir) throws Exception {
        runCommandOrThrow(List.of("git", "remote", "add", "origin", "file://" + bareRemote.toAbsolutePath()),
                repositoryDir,
                tempDir.resolve("git-remote-add-origin.log"),
                Duration.ofSeconds(30));
    }

    private static void pushMainBranch(Path repositoryDir, Path bareRemote, Path tempDir) throws Exception {
        runCommandOrThrow(List.of("git", "push", "--set-upstream", "origin", "main"),
                repositoryDir,
                tempDir.resolve("git-push.log"),
                Duration.ofMinutes(2));
    }

    private static void deleteDirectoryQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Containerized act runs can leave root-owned files behind on CI. Ignore cleanup failures.
                }
            });
        } catch (IOException ignored) {
            // Ignore best-effort cleanup failures.
        }
    }

    private static boolean commandSucceeds(List<String> command, Duration timeout) {
        try {
            Path logFile = Files.createTempFile("repository-template-command-", ".log");
            try {
                return runCommand(command, Path.of("."), logFile, timeout).exitCode() == 0;
            } finally {
                Files.deleteIfExists(logFile);
            }
        } catch (Exception exception) {
            return false;
        }
    }

    private static CommandResult runCommand(List<String> command,
                                            Path workingDirectory,
                                            Path logFile,
                                            Duration timeout) throws Exception {
        return runCommand(command, workingDirectory, logFile, timeout, Map.of());
    }

    private static CommandResult runCommand(List<String> command,
                                            Path workingDirectory,
                                            Path logFile,
                                            Duration timeout,
                                            Map<String, String> environmentOverrides) throws Exception {
        Files.createDirectories(logFile.getParent());
        ProcessBuilder processBuilder = new ProcessBuilder(new ArrayList<>(command))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        environmentOverrides.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                processBuilder.environment().put(key, value);
            }
        });
        Process process = processBuilder.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Command timed out after " + timeout + ": "
                    + String.join(" ", command) + System.lineSeparator() + Files.readString(logFile));
        }
        return new CommandResult(process.exitValue(), Files.readString(logFile));
    }

    private static void runCommandOrThrow(List<String> command,
                                          Path workingDirectory,
                                          Path logFile,
                                          Duration timeout) throws Exception {
        CommandResult result = runCommand(command, workingDirectory, logFile, timeout);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Command failed with exit code " + result.exitCode()
                    + ": " + String.join(" ", command) + System.lineSeparator() + result.output());
        }
    }

    private record CommandResult(int exitCode, String output) {
    }
}
