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

import dev.promptlm.repository.template.TemplateContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link ZipFileRepositoryTemplateExtractor} against the real
 * {@code repo-template.zip} packaged on the classpath. Exercises the addressed acceptance
 * criteria of issue #160:
 *
 * <ul>
 *     <li>AC-7: no {@code {{...}}} tokens remain in any text file after extraction.</li>
 *     <li>AC-8: {@code metadata.json} reflects runtime timestamp and generator version.</li>
 *     <li>AC-9: {@code README.md} has no literal {@code {{REPO_NAME}}} / {@code {{PROJECT_DESCRIPTION}}} tokens.</li>
 * </ul>
 */
class ZipFileRepositoryTemplateExtractorSubstitutionTest {

    private static final List<String> TEXT_FILE_EXTENSIONS = List.of(
            ".md", ".json", ".toml", ".yml", ".yaml", ".txt", ".xml", ".properties");

    /**
     * Matches our template-token convention: {@code {{UPPER_SNAKE}}}. Crucially, this is NOT
     * preceded by {@code $}, so it does not match GitHub Actions expressions like
     * {@code ${{ github.event.inputs.x }}} which are intended to survive extraction.
     */
    private static final Pattern UNRESOLVED_TOKEN_PATTERN =
            Pattern.compile("(?<![$])\\{\\{\\s*[A-Z][A-Z0-9_]*\\s*}}");

    @Test
    void extractedRepoHasNoUnresolvedTemplateTokens(@TempDir Path tempDir) throws IOException {
        ZipFileRepositoryTemplateExtractor extractor = new ZipFileRepositoryTemplateExtractor();
        TemplateContext context = new TemplateContext(
                "issue-160-demo",
                "promptLM",
                "issue-160 acceptance prompts",
                Instant.parse("2026-05-17T01:23:45Z"),
                "9.9.9");

        extractor.extractTo(tempDir, context);

        List<Path> textFiles = collectTextFiles(tempDir);
        assertThat(textFiles).isNotEmpty();
        for (Path file : textFiles) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = UNRESOLVED_TOKEN_PATTERN.matcher(content);
            if (matcher.find()) {
                throw new AssertionError("Unresolved template token '" + matcher.group()
                        + "' remains in " + tempDir.relativize(file));
            }
        }
    }

    @Test
    void readmeContainsSubstitutedRepoNameAndDescription(@TempDir Path tempDir) throws IOException {
        ZipFileRepositoryTemplateExtractor extractor = new ZipFileRepositoryTemplateExtractor();
        TemplateContext context = new TemplateContext(
                "issue-160-demo",
                "promptLM",
                "issue-160 acceptance prompts",
                Instant.parse("2026-05-17T01:23:45Z"),
                "9.9.9");

        extractor.extractTo(tempDir, context);

        String readme = Files.readString(tempDir.resolve("README.md"), StandardCharsets.UTF_8);
        assertThat(readme).contains("# issue-160-demo");
        assertThat(readme).contains("issue-160 acceptance prompts");
        assertThat(readme).doesNotContain("{{REPO_NAME}}");
        assertThat(readme).doesNotContain("{{PROJECT_DESCRIPTION}}");
    }

    @Test
    void metadataJsonHasRuntimeTimestampAndGeneratorVersion(@TempDir Path tempDir) throws IOException {
        ZipFileRepositoryTemplateExtractor extractor = new ZipFileRepositoryTemplateExtractor();
        TemplateContext context = new TemplateContext(
                "issue-160-demo",
                "promptLM",
                "issue-160 acceptance prompts",
                Instant.parse("2026-05-17T01:23:45Z"),
                "9.9.9");

        extractor.extractTo(tempDir, context);

        String metadata = Files.readString(tempDir.resolve(".promptlm/metadata.json"), StandardCharsets.UTF_8);
        assertThat(metadata).contains("\"name\": \"issue-160-demo\"");
        assertThat(metadata).contains("\"created\": \"2026-05-17T01:23:45Z\"");
        assertThat(metadata).contains("\"updated\": \"2026-05-17T01:23:45Z\"");
        assertThat(metadata).contains("\"generator_version\": \"9.9.9\"");
        assertThat(metadata).doesNotContain("2025-01-19T22:42:35Z");
        assertThat(metadata).doesNotContain("0.1.0-SNAPSHOT");
        assertThat(metadata).doesNotContain("{{");
    }

    /**
     * Regression guard for issue #323: the rolled-out repository must contain no literal
     * {@code REPLACE_ME_} sentinels and must substitute the artifact-coordinate tokens
     * derived from owner/repo into {@code pom.xml} and {@code .promptlm/artifacts.toml}.
     */
    @Test
    void noFileContainsReplaceMeSentinelAndPomHasSubstitutedCoordinates(@TempDir Path tempDir) throws IOException {
        ZipFileRepositoryTemplateExtractor extractor = new ZipFileRepositoryTemplateExtractor();
        // Mirror what GitProjectService passes: derived coordinates from owner+repo.
        TemplateContext context = new TemplateContext(
                "my-prompts",
                "ACME-Corp",
                "issue-323 regression guard",
                Instant.parse("2026-05-17T01:23:45Z"),
                "9.9.9");

        extractor.extractTo(tempDir, context);

        String pom = Files.readString(tempDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pom).contains("<groupId>io.github.acme-corp</groupId>");
        assertThat(pom).contains("<artifactId>my-prompts</artifactId>");

        String artifactsToml = Files.readString(tempDir.resolve(".promptlm/artifacts.toml"), StandardCharsets.UTF_8);
        assertThat(artifactsToml).contains("group_id = \"io.github.acme-corp\"");
        assertThat(artifactsToml).contains("artifact_id = \"my-prompts\"");
        assertThat(artifactsToml).contains("distribution_name = \"my-prompts\"");
        assertThat(artifactsToml).contains("import_name = \"my_prompts\"");
        assertThat(artifactsToml).contains("package_name = \"my-prompts\"");

        // No file under the extracted tree should contain a REPLACE_ME_ literal in a place
        // where downstream tooling would consume it as a real value.
        //
        // Known-legitimate references (allow-listed):
        //   - .promptlm/artifacts.toml: Artifactory / custom-profile URLs that genuinely
        //     require the user to fill in an external service URL we cannot know at
        //     rollout time (REPLACE_ME_ARTIFACTORY_URL, REPLACE_ME_custom_*,
        //     REPLACE_ME_pypi_repo_url for the GitHub profile — see comment in the file).
        //     The GitHub Maven URL is substituted via {{REPO_OWNER}}/{{REPO_NAME}} so it
        //     does NOT appear in the allow-list.
        //   - .github/artifactory-config.yml: REFERENCE ONLY per issue #325 (P1).
        //   - scripts/package-prompts.sh: documents in a comment that
        //     REPLACE_ME_* values trigger a fallback — describing the string, not
        //     using it as a coordinate.
        Set<String> allowList = Set.of(
                ".promptlm/artifacts.toml",
                ".github/artifactory-config.yml",
                "scripts/package-prompts.sh");
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !allowList.contains(
                            tempDir.relativize(p).toString().replace('\\', '/')))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            assertThat(content)
                                    .as("Unexpected REPLACE_ME_ sentinel left in %s", tempDir.relativize(p))
                                    .doesNotContain("REPLACE_ME_");
                        } catch (IOException e) {
                            // Binary file (e.g. images) — readString would throw MalformedInput.
                            // Treat as no-op: the substring REPLACE_ME_ in a binary is implausible.
                        }
                    });
        }
    }

    /**
     * Regression guard for issue #323: every extracted file's executable bit must agree
     * with the extractor's own {@link ZipFileRepositoryTemplateExtractor#isExecutableEntry}
     * classifier — files the classifier deems executable must have the bit set, and
     * everything else must not. Walking the whole tree (rather than hardcoding two paths)
     * means a newly added shell script in the template is caught automatically as long as
     * it is registered in {@code EXECUTABLE_PATH_PATTERNS} and pinned to {@code 0755} in
     * the assembly descriptor — the two sources of truth for the shipped archive.
     */
    @Test
    void releaseScriptsAreExecutableOnPosix(@TempDir Path tempDir) throws IOException {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are not supported on this filesystem");

        ZipFileRepositoryTemplateExtractor extractor = new ZipFileRepositoryTemplateExtractor();
        TemplateContext context = new TemplateContext(
                "my-prompts",
                "ACME-Corp",
                "issue-323 exec-bit regression guard",
                Instant.parse("2026-05-17T01:23:45Z"),
                "9.9.9");

        extractor.extractTo(tempDir, context);

        // Spot-check the canonical executables exist so a silent miss (e.g. release-only
        // gating bug) doesn't pass this test vacuously.
        List<Path> requiredExecutables = List.of(
                tempDir.resolve("tools/release/build-artifacts"),
                tempDir.resolve("tools/release/publish-artifacts"),
                tempDir.resolve("scripts/validate-prompts.sh"),
                tempDir.resolve("scripts/package-prompts.sh"));
        for (Path required : requiredExecutables) {
            assertThat(required)
                    .as("expected executable %s to be present after extraction", tempDir.relativize(required))
                    .exists();
        }

        // Walk the whole tree: every file's executable bit must match the classifier the
        // extractor itself uses, so adding a new shell script anywhere in the template
        // automatically extends coverage.
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String relative = tempDir.relativize(p).toString().replace('\\', '/');
                boolean expectedExecutable = ZipFileRepositoryTemplateExtractor.isExecutableEntry(relative);
                assertThat(Files.isExecutable(p))
                        .as("executable bit on %s must match isExecutableEntry classifier (regression guard for #323)", relative)
                        .isEqualTo(expectedExecutable);
            });
        }
    }

    private static List<Path> collectTextFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .forEach(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        for (String ext : TEXT_FILE_EXTENSIONS) {
                            if (name.endsWith(ext)) {
                                files.add(p);
                                return;
                            }
                        }
                        // Also pick up the unextensioned release scripts that we substitute.
                        Path relative = root.relativize(p);
                        if (relative.toString().replace('\\', '/').startsWith("tools/release/")) {
                            files.add(p);
                        }
                    });
        }
        return files;
    }
}
