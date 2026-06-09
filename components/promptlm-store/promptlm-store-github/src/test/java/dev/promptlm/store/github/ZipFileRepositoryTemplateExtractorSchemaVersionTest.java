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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the {@code schemaVersion} gate in
 * {@link ZipFileRepositoryTemplateExtractor#verifySchemaVersion(byte[])}.
 * See issue #353.
 */
class ZipFileRepositoryTemplateExtractorSchemaVersionTest {

    @Test
    void supportedSchemaVersionIsAcceptedSilently() {
        String yaml = """
                schemaVersion: "1"
                release:
                  enabled: true
                """;

        assertThatCode(() ->
                ZipFileRepositoryTemplateExtractor.verifySchemaVersion(
                        yaml.getBytes(StandardCharsets.UTF_8)))
                .doesNotThrowAnyException();
    }

    @Test
    void missingSchemaVersionIsTolerated() {
        // Pre-#353 files have no schemaVersion. The loader must accept them
        // so existing repositories keep working; the WARN log is enough.
        String yaml = """
                release:
                  enabled: true
                  provider: github-actions
                """;

        assertThatCode(() ->
                ZipFileRepositoryTemplateExtractor.verifySchemaVersion(
                        yaml.getBytes(StandardCharsets.UTF_8)))
                .doesNotThrowAnyException();
    }

    @Test
    void futureSchemaVersionIsRejected() {
        String yaml = """
                schemaVersion: "2"
                release:
                  enabled: true
                """;

        assertThatThrownBy(() ->
                ZipFileRepositoryTemplateExtractor.verifySchemaVersion(
                        yaml.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schemaVersion")
                .hasMessageContaining("2")
                .hasMessageContaining("1");
    }
}
