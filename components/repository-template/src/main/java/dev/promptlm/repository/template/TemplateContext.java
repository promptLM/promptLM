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

import java.time.Instant;
import java.util.Objects;

/**
 * Values that the repository template extractor substitutes into the generated repository at
 * extraction time. Carries the canonical token sources for:
 *
 * <ul>
 *     <li>{@code {{REPO_NAME}}}, {@code {{REPO_OWNER}}}, {@code {{PROJECT_DESCRIPTION}}},
 *         {@code {{CREATED_AT}}}, {@code {{GENERATOR_VERSION}}} — original P0 tokens.</li>
 *     <li>{@code {{PROJECT_NAME}}}, {@code {{MAVEN_GROUP_ID}}},
 *         {@code {{MAVEN_ARTIFACT_ID}}}, {@code {{PYTHON_DISTRIBUTION_NAME}}},
 *         {@code {{PYTHON_IMPORT_NAME}}}, {@code {{NPM_PACKAGE_NAME}}} — artifact-coordinate
 *         tokens introduced for issue #323 so rolled-out repositories never ship literal
 *         {@code REPLACE_ME_*} sentinels.</li>
 * </ul>
 *
 * <p>{@code createdAt} is rendered as ISO-8601 UTC (e.g. {@code 2026-05-17T01:23:45Z}).
 *
 * <p>This is an immutable, equality-by-value record; callers should construct one per
 * repository generation. A 5-arg back-compat secondary constructor derives the artifact
 * coordinates from {@code repositoryName} / {@code ownerName} via
 * {@link ArtifactCoordinateSanitizer}, so existing call sites that do not yet know about
 * the artifact tokens still produce a valid {@code TemplateContext}.
 */
public record TemplateContext(
        String repositoryName,
        String ownerName,
        String projectDescription,
        Instant createdAt,
        String generatorVersion,
        String projectName,
        String mavenGroupId,
        String mavenArtifactId,
        String pythonDistributionName,
        String pythonImportName,
        String npmPackageName) {

    public TemplateContext {
        Objects.requireNonNull(repositoryName, "repositoryName");
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(projectDescription, "projectDescription");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(generatorVersion, "generatorVersion");
        Objects.requireNonNull(projectName, "projectName");
        Objects.requireNonNull(mavenGroupId, "mavenGroupId");
        Objects.requireNonNull(mavenArtifactId, "mavenArtifactId");
        Objects.requireNonNull(pythonDistributionName, "pythonDistributionName");
        Objects.requireNonNull(pythonImportName, "pythonImportName");
        Objects.requireNonNull(npmPackageName, "npmPackageName");
    }

    /**
     * Back-compat secondary constructor: takes the 5 original fields and derives the
     * artifact-coordinate tokens from {@code repositoryName} / {@code ownerName} via
     * {@link ArtifactCoordinateSanitizer}. New call sites that want explicit control over
     * the artifact coordinates should use the canonical constructor.
     */
    public TemplateContext(
            String repositoryName,
            String ownerName,
            String projectDescription,
            Instant createdAt,
            String generatorVersion) {
        this(
                repositoryName,
                ownerName,
                projectDescription,
                createdAt,
                generatorVersion,
                ArtifactCoordinateSanitizer.projectName(repositoryName),
                ArtifactCoordinateSanitizer.mavenGroupId(ownerName),
                ArtifactCoordinateSanitizer.mavenArtifactId(repositoryName),
                ArtifactCoordinateSanitizer.pythonDistributionName(repositoryName),
                ArtifactCoordinateSanitizer.pythonImportName(repositoryName),
                ArtifactCoordinateSanitizer.npmPackageName(repositoryName)
        );
    }
}
