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

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateSubstitutionEngineTest {

    private static final TemplateContext CTX = new TemplateContext(
            "my-prompts",
            "alice",
            "experiments in pedagogy",
            Instant.parse("2026-05-17T09:30:00Z"),
            "9.9.9");

    private final TemplateSubstitutionEngine engine = new TemplateSubstitutionEngine("9.9.9-build");

    @Test
    void substitutesAllRecognizedTokensInMarkdown() {
        byte[] source = "# {{REPO_NAME}}\n\nOwned by {{REPO_OWNER}}.\nDesc: {{PROJECT_DESCRIPTION}}.\nCreated {{CREATED_AT}}, generator {{GENERATOR_VERSION}}."
                .getBytes(StandardCharsets.UTF_8);

        String result = new String(engine.substitute("README.md", source, CTX), StandardCharsets.UTF_8);

        assertThat(result).isEqualTo("# my-prompts\n\nOwned by alice.\nDesc: experiments in pedagogy.\nCreated 2026-05-17T09:30:00Z, generator 9.9.9.");
    }

    @Test
    void jsonEscapesValuesInJsonFiles() {
        TemplateContext quoted = new TemplateContext(
                "name-with-\"quotes\"",
                "owner\\path",
                "line1\nline2",
                Instant.parse("2026-05-17T09:30:00Z"),
                "1.0.0");
        byte[] source = "{\"name\":\"{{REPO_NAME}}\",\"owner\":\"{{REPO_OWNER}}\",\"desc\":\"{{PROJECT_DESCRIPTION}}\"}".getBytes(StandardCharsets.UTF_8);

        String result = new String(engine.substitute("metadata.json", source, quoted), StandardCharsets.UTF_8);

        assertThat(result).isEqualTo("{\"name\":\"name-with-\\\"quotes\\\"\",\"owner\":\"owner\\\\path\",\"desc\":\"line1\\nline2\"}");
    }

    @Test
    void doesNotEscapeInNonJsonFiles() {
        TemplateContext quoted = new TemplateContext(
                "name-with-\"quotes\"",
                "owner",
                "desc",
                Instant.parse("2026-05-17T09:30:00Z"),
                "1.0.0");
        byte[] source = "# {{REPO_NAME}}".getBytes(StandardCharsets.UTF_8);

        String result = new String(engine.substitute("README.md", source, quoted), StandardCharsets.UTF_8);

        assertThat(result).isEqualTo("# name-with-\"quotes\"");
    }

    @Test
    void leavesUnknownTokensIntactSoCallersCanCatchThem() {
        byte[] source = "version={{NOT_A_KNOWN_TOKEN}}".getBytes(StandardCharsets.UTF_8);

        String result = new String(engine.substitute("artifacts.toml", source, CTX), StandardCharsets.UTF_8);

        assertThat(result).isEqualTo("version={{NOT_A_KNOWN_TOKEN}}");
    }

    @Test
    void doesNotRescanSubstitutedOutput() {
        TemplateContext mischievous = new TemplateContext(
                "{{REPO_OWNER}}",
                "alice",
                "desc",
                Instant.parse("2026-05-17T09:30:00Z"),
                "1.0.0");
        byte[] source = "name={{REPO_NAME}}".getBytes(StandardCharsets.UTF_8);

        String result = new String(engine.substitute("README.md", source, mischievous), StandardCharsets.UTF_8);

        assertThat(result).isEqualTo("name={{REPO_OWNER}}");
    }

    @Test
    void resolvesBuildGeneratorVersionSentinel() {
        TemplateContext ctx = new TemplateContext(
                "repo",
                "owner",
                "desc",
                Instant.parse("2026-05-17T09:30:00Z"),
                TemplateSubstitutionEngine.BUILD_GENERATOR_VERSION);
        byte[] source = "generator={{GENERATOR_VERSION}}".getBytes(StandardCharsets.UTF_8);

        String result = new String(engine.substitute("README.md", source, ctx), StandardCharsets.UTF_8);

        assertThat(result).isEqualTo("generator=9.9.9-build");
    }

    @Test
    void loadsBuildVersionFromClasspathProperties() {
        TemplateSubstitutionEngine real = new TemplateSubstitutionEngine();
        assertThat(real.buildGeneratorVersion()).isNotBlank();
        // Either the filtered Maven value or the "dev" fallback — never the unfiltered token.
        assertThat(real.buildGeneratorVersion()).doesNotStartWith("${");
    }

    @Test
    void treatsKnownTextExtensionsAsText() {
        assertThat(engine.isTextEntry("README.md")).isTrue();
        assertThat(engine.isTextEntry(".promptlm/metadata.json")).isTrue();
        assertThat(engine.isTextEntry(".promptlm/artifacts.toml")).isTrue();
        assertThat(engine.isTextEntry(".github/workflows/x.yml")).isTrue();
        assertThat(engine.isTextEntry(".github/workflows/x.yaml")).isTrue();
        assertThat(engine.isTextEntry("pom.xml")).isTrue();
        assertThat(engine.isTextEntry("notes.txt")).isTrue();
    }

    @Test
    void treatsUnextensionedReleaseScriptsAsText() {
        assertThat(engine.isTextEntry("tools/release/build-artifacts")).isTrue();
        assertThat(engine.isTextEntry("tools/release/publish-artifacts")).isTrue();
    }

    @Test
    void treatsUnknownExtensionsAsBinary() {
        assertThat(engine.isTextEntry("image.png")).isFalse();
        assertThat(engine.isTextEntry("docs/diagram.jpg")).isFalse();
        assertThat(engine.isTextEntry("prompts/.gitignore")).isFalse();
        assertThat(engine.isTextEntry("Makefile")).isFalse();
    }

    @Test
    void substitutesAllArtifactCoordinateTokens() {
        // Use the canonical 11-arg constructor so every artifact-coordinate token can be
        // verified independently of the back-compat derivation rules.
        TemplateContext ctx = new TemplateContext(
                "my-prompts",
                "acme-corp",
                "desc",
                Instant.parse("2026-05-17T09:30:00Z"),
                "1.0.0",
                "My Prompts",
                "io.github.acme-corp",
                "my-prompts",
                "my-prompts",
                "my_prompts",
                "my-prompts");
        byte[] source = ("project={{PROJECT_NAME}}\n"
                + "g={{MAVEN_GROUP_ID}}\n"
                + "a={{MAVEN_ARTIFACT_ID}}\n"
                + "pyd={{PYTHON_DISTRIBUTION_NAME}}\n"
                + "pyi={{PYTHON_IMPORT_NAME}}\n"
                + "npm={{NPM_PACKAGE_NAME}}\n").getBytes(StandardCharsets.UTF_8);

        String result = new String(engine.substitute("artifacts.toml", source, ctx), StandardCharsets.UTF_8);

        assertThat(result).isEqualTo(""
                + "project=My Prompts\n"
                + "g=io.github.acme-corp\n"
                + "a=my-prompts\n"
                + "pyd=my-prompts\n"
                + "pyi=my_prompts\n"
                + "npm=my-prompts\n");
    }

    @Test
    void backCompatConstructorDerivesArtifactTokensFromOwnerAndRepo() {
        TemplateContext ctx = new TemplateContext(
                "My-Prompts", "ACME-Corp", "desc",
                Instant.parse("2026-05-17T09:30:00Z"), "1.0.0");
        byte[] source = "g={{MAVEN_GROUP_ID}} a={{MAVEN_ARTIFACT_ID}}".getBytes(StandardCharsets.UTF_8);

        String result = new String(engine.substitute("artifacts.toml", source, ctx), StandardCharsets.UTF_8);

        assertThat(result).isEqualTo("g=io.github.acme-corp a=my-prompts");
    }
}
