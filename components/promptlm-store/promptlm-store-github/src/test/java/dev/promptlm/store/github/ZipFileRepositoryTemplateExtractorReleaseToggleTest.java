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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@code release.enabled} gate in
 * {@link ZipFileRepositoryTemplateExtractor}. The extractor reads the
 * embedded {@code promptlm.yml} and skips Mode-2-only entries when
 * {@code release.enabled: false}. See issue #161.
 */
class ZipFileRepositoryTemplateExtractorReleaseToggleTest {

    private static final String MODE_1_YAML = """
            release:
              enabled: false
              provider: github-actions
            """;

    private static final String MODE_2_YAML = """
            release:
              enabled: true
              provider: github-actions
            """;

    @Test
    void releaseDisabledOmitsMode2OnlyFiles(@TempDir Path tempDir) throws IOException {
        byte[] zip = buildFullTemplateArchive(MODE_1_YAML);

        new ZipFileRepositoryTemplateExtractor(new ByteArrayResource(zip)).extractTo(tempDir, sampleContext());

        assertThat(tempDir.resolve("promptlm.yml")).exists();
        assertThat(tempDir.resolve(".promptlm/metadata.json")).exists();
        assertThat(tempDir.resolve(".promptlm/prompts-meta.json")).exists();
        // Issue #309 — the live template no longer seeds prompts/examples/hello.md;
        // the only prompts/ entry now is the .gitignore placeholder that keeps the
        // empty directory in the zip. We assert on that to stay aligned with reality.
        assertThat(tempDir.resolve("prompts/.gitignore")).exists();
        assertThat(tempDir.resolve("README.md")).exists();

        // Mode 2 release infrastructure must be absent.
        assertThat(tempDir.resolve(".github")).doesNotExist();
        assertThat(tempDir.resolve("tools")).doesNotExist();
        assertThat(tempDir.resolve("scripts")).doesNotExist();
        assertThat(tempDir.resolve("pom.xml")).doesNotExist();
        assertThat(tempDir.resolve(".promptlm/artifacts.toml")).doesNotExist();
    }

    @Test
    void releaseEnabledIncludesMode2Files(@TempDir Path tempDir) throws IOException {
        byte[] zip = buildFullTemplateArchive(MODE_2_YAML);

        new ZipFileRepositoryTemplateExtractor(new ByteArrayResource(zip)).extractTo(tempDir, sampleContext());

        assertThat(tempDir.resolve("promptlm.yml")).exists();
        assertThat(tempDir.resolve(".github/workflows/build-artifacts.yml")).exists();
        assertThat(tempDir.resolve("tools/release/build-artifacts")).exists();
        assertThat(tempDir.resolve("pom.xml")).exists();
        assertThat(tempDir.resolve(".promptlm/artifacts.toml")).exists();
    }

    @Test
    void missingPromptlmYmlFallsBackToMode1(@TempDir Path tempDir) throws IOException {
        byte[] zip = buildArchive(builder -> {
            builder.write("README.md", "readme");
            builder.write(".github/workflows/build-artifacts.yml", "name: build");
            builder.write("pom.xml", "<project/>");
        });

        new ZipFileRepositoryTemplateExtractor(new ByteArrayResource(zip)).extractTo(tempDir, sampleContext());

        // Without an explicit opt-in, default to the safer Mode 1 layout.
        assertThat(tempDir.resolve("README.md")).exists();
        assertThat(tempDir.resolve(".github")).doesNotExist();
        assertThat(tempDir.resolve("pom.xml")).doesNotExist();
    }

    @Test
    void isReleaseEnabledRecognisesTrueRegardlessOfWhitespace() {
        assertThat(ZipFileRepositoryTemplateExtractor.isReleaseEnabled(MODE_1_YAML.getBytes(StandardCharsets.UTF_8)))
                .isFalse();
        assertThat(ZipFileRepositoryTemplateExtractor.isReleaseEnabled(MODE_2_YAML.getBytes(StandardCharsets.UTF_8)))
                .isTrue();
    }

    @Test
    void isReleaseEnabledIgnoresUnrelatedEnabledKeys() {
        String yaml = """
                release:
                  enabled: false
                  validation:
                    enabled: true
                """;
        assertThat(ZipFileRepositoryTemplateExtractor.isReleaseEnabled(yaml.getBytes(StandardCharsets.UTF_8)))
                .as("Only the top-level release.enabled flag should control the gate, not nested enabled keys")
                .isFalse();
    }

    @Test
    void isReleaseEnabledDefaultsToFalseOnEmptyInput() {
        assertThat(ZipFileRepositoryTemplateExtractor.isReleaseEnabled(null)).isFalse();
        assertThat(ZipFileRepositoryTemplateExtractor.isReleaseEnabled(new byte[0])).isFalse();
    }

    private static TemplateContext sampleContext() {
        return new TemplateContext(
                "sample-repo",
                "sample-owner",
                "sample description",
                Instant.parse("2026-01-01T00:00:00Z"),
                "1.2.3");
    }

    private static byte[] buildFullTemplateArchive(String promptlmYml) throws IOException {
        return buildArchive(builder -> {
            builder.write("promptlm.yml", promptlmYml);
            builder.write("README.md", "readme");
            builder.write(".promptlm/metadata.json", "{}");
            builder.write(".promptlm/prompts-meta.json", "{}");
            builder.write(".promptlm/artifacts.toml", "[project]");
            builder.write("prompts/.gitignore", "");
            builder.write(".github/workflows/build-artifacts.yml", "name: build");
            builder.write(".github/workflows/bundle-release.yml", "name: bundle-release");
            builder.write("tools/release/build-artifacts", "#!/bin/sh");
            builder.write("pom.xml", "<project/>");
        });
    }

    private static byte[] buildArchive(ArchivePopulator populator) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
            populator.populate(new ArchiveBuilder(zipOutputStream));
        }
        return outputStream.toByteArray();
    }

    @FunctionalInterface
    private interface ArchivePopulator {
        void populate(ArchiveBuilder builder) throws IOException;
    }

    private record ArchiveBuilder(ZipOutputStream zipOutputStream) {
        void write(String entryName, String content) throws IOException {
            zipOutputStream.putNextEntry(new ZipEntry(entryName));
            zipOutputStream.write(content.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
    }
}
