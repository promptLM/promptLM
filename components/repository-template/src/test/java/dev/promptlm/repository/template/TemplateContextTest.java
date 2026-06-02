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

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateContextTest {

    @Test
    void exposesAllFields() {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");
        TemplateContext ctx = new TemplateContext("repo", "owner", "desc", now, "1.0.0");

        assertEquals("repo", ctx.repositoryName());
        assertEquals("owner", ctx.ownerName());
        assertEquals("desc", ctx.projectDescription());
        assertEquals(now, ctx.createdAt());
        assertEquals("1.0.0", ctx.generatorVersion());
    }

    @Test
    void rejectsNulls() {
        Instant now = Instant.now();

        assertThrows(NullPointerException.class,
                () -> new TemplateContext(null, "o", "d", now, "v"));
        assertThrows(NullPointerException.class,
                () -> new TemplateContext("r", null, "d", now, "v"));
        assertThrows(NullPointerException.class,
                () -> new TemplateContext("r", "o", null, now, "v"));
        assertThrows(NullPointerException.class,
                () -> new TemplateContext("r", "o", "d", null, "v"));
        assertThrows(NullPointerException.class,
                () -> new TemplateContext("r", "o", "d", now, null));
    }

    @Test
    void backCompatConstructorDerivesArtifactCoordinates() {
        Instant now = Instant.parse("2026-05-17T01:23:45Z");
        TemplateContext ctx = new TemplateContext("My-Prompts", "ACME-Corp", "desc", now, "1.0.0");

        assertEquals("My-Prompts", ctx.projectName());
        assertEquals("io.github.acme-corp", ctx.mavenGroupId());
        assertEquals("my-prompts", ctx.mavenArtifactId());
        assertEquals("my-prompts", ctx.pythonDistributionName());
        assertEquals("my_prompts", ctx.pythonImportName());
        assertEquals("my-prompts", ctx.npmPackageName());
    }

    @Test
    void canonicalConstructorExposesAllElevenFields() {
        Instant now = Instant.parse("2026-05-17T01:23:45Z");
        TemplateContext ctx = new TemplateContext(
                "repo", "owner", "desc", now, "1.0.0",
                "Display Name", "com.example", "my-artifact",
                "my-dist", "my_import", "my-npm");

        assertEquals("repo", ctx.repositoryName());
        assertEquals("owner", ctx.ownerName());
        assertEquals("Display Name", ctx.projectName());
        assertEquals("com.example", ctx.mavenGroupId());
        assertEquals("my-artifact", ctx.mavenArtifactId());
        assertEquals("my-dist", ctx.pythonDistributionName());
        assertEquals("my_import", ctx.pythonImportName());
        assertEquals("my-npm", ctx.npmPackageName());
    }
}
