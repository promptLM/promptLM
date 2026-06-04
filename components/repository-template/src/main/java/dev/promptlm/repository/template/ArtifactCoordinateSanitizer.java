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

import java.util.Locale;

/**
 * Sanitizes a repository owner/name into the various artifact-coordinate flavors that a
 * generated prompt repository ships with — Maven {@code groupId}/{@code artifactId},
 * PEP 503 Python distribution name, PEP 8 Python import name, and npm package name.
 *
 * <p>The defaults produced here are intentionally <em>best-effort but always valid</em>: they
 * never throw, never return an empty string, and never produce a value that violates the
 * receiving ecosystem's identifier rules. Users are expected to override these in the
 * generated {@code pom.xml} / {@code .promptlm/artifacts.toml} when they have specific
 * coordinates in mind; the job of this class is to ensure the rollout never ships a literal
 * {@code REPLACE_ME_*} sentinel that would silently break downstream builds.
 *
 * <p>None of the produced values are quoted or otherwise context-escaped — callers that
 * substitute them into JSON/XML/TOML must rely on
 * {@link TemplateSubstitutionEngine}'s per-format escaping.
 */
public final class ArtifactCoordinateSanitizer {

    private static final String FALLBACK = "prompts";

    private ArtifactCoordinateSanitizer() {
    }

    /**
     * Derive a Maven {@code groupId} from a repository owner. Produces
     * {@code io.github.<sanitized-owner>} where {@code <sanitized-owner>} is lowercased and
     * all characters outside {@code [a-z0-9.]} are collapsed to {@code -}, then trimmed of
     * leading/trailing punctuation.
     *
     * @param ownerName the GitHub owner (login or organization). May be {@code null} or
     *                  effectively empty.
     * @return a never-{@code null}, always-valid Maven groupId.
     */
    public static String mavenGroupId(String ownerName) {
        String owner = sanitizeGeneric(ownerName, "[a-z0-9.]", '-');
        if (owner.isEmpty()) {
            owner = FALLBACK;
        }
        return "io.github." + owner;
    }

    /**
     * Derive a Maven {@code artifactId} from a repository name. Lowercased, characters
     * outside {@code [a-z0-9.-]} collapsed to {@code -}.
     */
    public static String mavenArtifactId(String repositoryName) {
        String out = sanitizeGeneric(repositoryName, "[a-z0-9.-]", '-');
        return out.isEmpty() ? FALLBACK : out;
    }

    /**
     * Derive a PEP 503 (<a href="https://peps.python.org/pep-0503/">PEP 503</a>) normalized
     * Python distribution name from a repository name: lowercased, runs of
     * {@code [^a-z0-9]+} collapsed to a single {@code -}, with leading/trailing dashes
     * trimmed.
     */
    public static String pythonDistributionName(String repositoryName) {
        String out = collapseToSingle(repositoryName, "a-z0-9", '-');
        return out.isEmpty() ? FALLBACK : out;
    }

    /**
     * Derive a PEP 8 Python import name (valid Python module identifier) from a repository
     * name: lowercased, runs of {@code [^a-z0-9_]+} collapsed to a single {@code _}, with
     * leading/trailing underscores trimmed. If the result would start with a digit (illegal
     * in Python identifiers) it is prefixed with {@code _}.
     */
    public static String pythonImportName(String repositoryName) {
        String out = collapseToSingle(repositoryName, "a-z0-9_", '_');
        if (out.isEmpty()) {
            return FALLBACK;
        }
        if (Character.isDigit(out.charAt(0))) {
            return "_" + out;
        }
        return out;
    }

    /**
     * Derive an npm package name from a repository name: lowercased, runs of
     * {@code [^a-z0-9._-]+} collapsed to a single {@code -}, with leading/trailing dots,
     * underscores and dashes trimmed.
     */
    public static String npmPackageName(String repositoryName) {
        String out = collapseToSingle(repositoryName, "a-z0-9._-", '-');
        // npm forbids names starting with '.' or '_'
        int start = 0;
        while (start < out.length()) {
            char c = out.charAt(start);
            if (c == '.' || c == '_' || c == '-') {
                start++;
            } else {
                break;
            }
        }
        out = out.substring(start);
        return out.isEmpty() ? FALLBACK : out;
    }

    /**
     * Pass-through: returns the repository name as the project name. Callers may want to
     * keep capitalization for display, so we intentionally do not lowercase here. Falls back
     * to {@code "prompts"} when {@code repositoryName} is {@code null} or blank.
     */
    public static String projectName(String repositoryName) {
        if (repositoryName == null) {
            return FALLBACK;
        }
        String trimmed = repositoryName.trim();
        return trimmed.isEmpty() ? FALLBACK : trimmed;
    }

    /**
     * Lowercase + replace any char not in {@code allowedClass} (regex char class body, no
     * brackets) with {@code replacement}; collapse consecutive replacements; trim leading
     * and trailing replacement chars and dots.
     */
    private static String sanitizeGeneric(String input, String allowedClass, char replacement) {
        if (input == null) {
            return "";
        }
        String lower = input.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean lastWasReplacement = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (String.valueOf(c).matches(allowedClass)) {
                out.append(c);
                lastWasReplacement = false;
            } else {
                if (!lastWasReplacement && out.length() > 0) {
                    out.append(replacement);
                    lastWasReplacement = true;
                }
            }
        }
        // trim trailing replacement / leading-trailing dots and dashes
        int end = out.length();
        while (end > 0) {
            char c = out.charAt(end - 1);
            if (c == replacement || c == '.' || c == '-') {
                end--;
            } else {
                break;
            }
        }
        int begin = 0;
        while (begin < end) {
            char c = out.charAt(begin);
            if (c == replacement || c == '.' || c == '-') {
                begin++;
            } else {
                break;
            }
        }
        return out.substring(begin, end);
    }

    /**
     * Lowercase + replace runs of {@code [^<allowedClass>]+} with a single {@code separator},
     * trim leading/trailing separators. Stricter variant of {@link #sanitizeGeneric} used for
     * PEP 503 / PEP 8 / npm normalization where ecosystem rules forbid runs of separators.
     */
    private static String collapseToSingle(String input, String allowedClass, char separator) {
        if (input == null) {
            return "";
        }
        String lower = input.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean lastWasSeparator = true; // suppress leading separators
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (String.valueOf(c).matches("[" + allowedClass + "]")) {
                out.append(c);
                lastWasSeparator = false;
            } else if (!lastWasSeparator) {
                out.append(separator);
                lastWasSeparator = true;
            }
        }
        // trim trailing separator
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == separator) {
            end--;
        }
        return out.substring(0, end);
    }
}
