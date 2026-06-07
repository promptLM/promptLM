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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryTemplateReadmeContentTest {

    @Test
    void readmeInPackagedZipHasNoBrokenReferencesAndKeepsTemplateTokens() throws Exception {
        String readme = readReadmeFromTemplateZip();

        assertFalse(readme.contains("ci.yml"),
                "README must not reference ci.yml — that workflow is parked, not shipped (see issue #166)");
        assertFalse(readme.contains("workflow-archive"),
                "README must not reference the internal monorepo path 'workflow-archive' (see issue #166)");

        assertTrue(readme.contains("{{REPO_NAME}}"),
                "README must keep the {{REPO_NAME}} template token canonical for the substitution pipeline (#160)");
        assertTrue(readme.contains("{{PROJECT_DESCRIPTION}}"),
                "README must keep the {{PROJECT_DESCRIPTION}} template token canonical for the substitution pipeline (#160)");

        // 0.1.0 ships a single mode (bundle-release → GitHub Packages); the
        // README must surface the bundle-release framing and the canonical
        // workflow name. The dual-mode (Mode 1 / Mode 2) text returns when the
        // progressive-disclosure UX in #275 lands.
        assertTrue(readme.contains("bundle-release"),
                "README must explain the bundle-release release class (see docs/release-classes.md, #311)");
        assertTrue(readme.contains("bundle-release.yml"),
                "README must reference the canonical bundle-release.yml workflow (#311)");
        assertTrue(readme.contains("GitHub Packages"),
                "README must call out GitHub Packages as the default registry (#311)");
    }

    private String readReadmeFromTemplateZip() throws Exception {
        try (InputStream resource = getClass().getClassLoader().getResourceAsStream("repo-template.zip")) {
            assertNotNull(resource, "repo-template.zip must be packaged in module resources");
            try (ZipInputStream zip = new ZipInputStream(resource)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory() && "README.md".equals(entry.getName())) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        zip.transferTo(buffer);
                        return buffer.toString(StandardCharsets.UTF_8);
                    }
                }
            }
        }
        throw new AssertionError("README.md not found in repo-template.zip");
    }
}
