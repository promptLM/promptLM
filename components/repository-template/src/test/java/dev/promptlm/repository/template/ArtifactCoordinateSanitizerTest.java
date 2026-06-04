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

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactCoordinateSanitizerTest {

    @Test
    void mavenGroupIdLowercasesOwnerAndPrefixesWithIoGithub() {
        assertThat(ArtifactCoordinateSanitizer.mavenGroupId("Acme-Corp"))
                .isEqualTo("io.github.acme-corp");
    }

    @Test
    void mavenGroupIdReplacesUnderscoresAndSpecialCharsWithDashes() {
        assertThat(ArtifactCoordinateSanitizer.mavenGroupId("Org-With.Special_Chars"))
                .isEqualTo("io.github.org-with.special-chars");
    }

    @Test
    void mavenGroupIdFallsBackToPromptsForBlankOrNullOwner() {
        assertThat(ArtifactCoordinateSanitizer.mavenGroupId(null)).isEqualTo("io.github.prompts");
        assertThat(ArtifactCoordinateSanitizer.mavenGroupId("")).isEqualTo("io.github.prompts");
        assertThat(ArtifactCoordinateSanitizer.mavenGroupId("___"))
                .isEqualTo("io.github.prompts");
    }

    @Test
    void mavenArtifactIdLowercasesAndCollapsesNonAllowedToDash() {
        assertThat(ArtifactCoordinateSanitizer.mavenArtifactId("My-Prompts"))
                .isEqualTo("my-prompts");
        assertThat(ArtifactCoordinateSanitizer.mavenArtifactId("My Prompts!"))
                .isEqualTo("my-prompts");
    }

    @Test
    void pythonDistributionNamePep503Normalizes() {
        // PEP 503: lowercase, runs of non-alphanumeric collapse to single '-'.
        assertThat(ArtifactCoordinateSanitizer.pythonDistributionName("My_Cool.Prompts"))
                .isEqualTo("my-cool-prompts");
        assertThat(ArtifactCoordinateSanitizer.pythonDistributionName("foo___bar"))
                .isEqualTo("foo-bar");
        assertThat(ArtifactCoordinateSanitizer.pythonDistributionName("---weird---"))
                .isEqualTo("weird");
    }

    @Test
    void pythonImportNamePep8AndStartsWithLetterOrUnderscore() {
        assertThat(ArtifactCoordinateSanitizer.pythonImportName("my-prompts"))
                .isEqualTo("my_prompts");
        assertThat(ArtifactCoordinateSanitizer.pythonImportName("My.Cool-Prompts"))
                .isEqualTo("my_cool_prompts");
        // Leading digit → prefix with underscore to keep a valid Python identifier.
        assertThat(ArtifactCoordinateSanitizer.pythonImportName("123-go"))
                .isEqualTo("_123_go");
    }

    @Test
    void npmPackageNameLowercasesAndStripsLeadingDotOrUnderscore() {
        assertThat(ArtifactCoordinateSanitizer.npmPackageName("My-Prompts"))
                .isEqualTo("my-prompts");
        assertThat(ArtifactCoordinateSanitizer.npmPackageName("@scoped/pkg"))
                .isEqualTo("scoped-pkg");
        assertThat(ArtifactCoordinateSanitizer.npmPackageName("_private"))
                .isEqualTo("private");
        assertThat(ArtifactCoordinateSanitizer.npmPackageName(".dotfile"))
                .isEqualTo("dotfile");
    }

    @Test
    void projectNameKeepsOriginalCasing() {
        assertThat(ArtifactCoordinateSanitizer.projectName("My-Cool-Prompts"))
                .isEqualTo("My-Cool-Prompts");
    }

    @Test
    void projectNameFallsBackForBlankInput() {
        assertThat(ArtifactCoordinateSanitizer.projectName(null)).isEqualTo("prompts");
        assertThat(ArtifactCoordinateSanitizer.projectName("")).isEqualTo("prompts");
        assertThat(ArtifactCoordinateSanitizer.projectName("   ")).isEqualTo("prompts");
    }

    @Test
    void allSanitizersTolerateNullInput() {
        // No NPE — every sanitizer falls back to a sensible default.
        assertThat(ArtifactCoordinateSanitizer.mavenArtifactId(null)).isEqualTo("prompts");
        assertThat(ArtifactCoordinateSanitizer.pythonDistributionName(null)).isEqualTo("prompts");
        assertThat(ArtifactCoordinateSanitizer.pythonImportName(null)).isEqualTo("prompts");
        assertThat(ArtifactCoordinateSanitizer.npmPackageName(null)).isEqualTo("prompts");
    }

    @Test
    void exampleEndToEndAcmeCorpMyPromptsMatchesPrSpec() {
        // The exact case called out in the issue #325 integration-test contract.
        assertThat(ArtifactCoordinateSanitizer.mavenGroupId("ACME-Corp"))
                .isEqualTo("io.github.acme-corp");
        assertThat(ArtifactCoordinateSanitizer.mavenArtifactId("my-prompts"))
                .isEqualTo("my-prompts");
    }
}
