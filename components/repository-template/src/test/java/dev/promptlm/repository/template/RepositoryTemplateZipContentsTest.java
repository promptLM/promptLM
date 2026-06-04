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

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RepositoryTemplateZipContentsTest {

    @Test
    void repoTemplateZipContainsExpectedEntries() throws Exception {
        try (InputStream resource = getClass().getClassLoader().getResourceAsStream("repo-template.zip")) {
            assertNotNull(resource, "repo-template.zip must be packaged in module resources");
            Set<String> actualEntries = new TreeSet<>();
            try (ZipInputStream zip = new ZipInputStream(resource)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        actualEntries.add(entry.getName());
                    }
                }
            }

            Set<String> expectedEntries = new TreeSet<>(List.of(
                    ".github/workflows/build-artifacts.yml",
                    ".github/workflows/bundle-release.yml",
                    ".github/workflows/release.yml",
                    ".github/workflows/validate.yml",
                    ".promptlm/artifacts.toml",
                    ".promptlm/metadata.json",
                    ".promptlm/prompts-meta.json",
                    "README.md",
                    "pom.xml",
                    "promptlm.yml",
                    "prompts/.gitignore",
                    "prompts/examples/hello.md",
                    "scripts/package-prompts.sh",
                    "scripts/validate-prompts.sh",
                    "tools/release/build-artifacts",
                    "tools/release/publish-artifacts"
            ));

            assertEquals(expectedEntries, actualEntries);
        }
    }
}
