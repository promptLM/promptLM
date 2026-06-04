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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Substitutes template tokens of the form {@code {{TOKEN}}} in text files extracted from the
 * repository template archive. Binary files are copied through unchanged.
 *
 * <p>Recognized tokens:
 * <ul>
 *     <li>{@code {{REPO_NAME}}}</li>
 *     <li>{@code {{REPO_OWNER}}}</li>
 *     <li>{@code {{PROJECT_DESCRIPTION}}}</li>
 *     <li>{@code {{CREATED_AT}}} (ISO-8601 UTC)</li>
 *     <li>{@code {{GENERATOR_VERSION}}}</li>
 *     <li>{@code {{PROJECT_NAME}}}</li>
 *     <li>{@code {{MAVEN_GROUP_ID}}}</li>
 *     <li>{@code {{MAVEN_ARTIFACT_ID}}}</li>
 *     <li>{@code {{PYTHON_DISTRIBUTION_NAME}}}</li>
 *     <li>{@code {{PYTHON_IMPORT_NAME}}}</li>
 *     <li>{@code {{NPM_PACKAGE_NAME}}}</li>
 * </ul>
 *
 * <p>Substitution is single-pass: substituted output is never re-scanned for tokens.
 *
 * <p>For files with a {@code .json} extension, replacement values are JSON-escaped so a project
 * name that happens to contain a double-quote does not corrupt the file. For other text formats
 * we insert values verbatim — values are user-controlled and not executed as code in any of the
 * shipped template files.
 *
 * <p>The {@code GENERATOR_VERSION} token defaults to the value loaded from
 * {@code META-INF/promptlm-repository-template.properties} (produced by Maven resource filtering)
 * when the corresponding field in the {@link TemplateContext} is set to the sentinel
 * {@link #BUILD_GENERATOR_VERSION}.
 */
public final class TemplateSubstitutionEngine {

    /**
     * Sentinel that callers can pass as {@link TemplateContext#generatorVersion()} to ask the
     * engine to substitute the build-time generator version loaded from the filtered resource.
     */
    public static final String BUILD_GENERATOR_VERSION = "${build.generator.version}";

    private static final String BUILD_PROPERTIES_RESOURCE =
            "META-INF/promptlm-repository-template.properties";
    private static final String BUILD_PROPERTY_GENERATOR_VERSION = "generator.version";
    private static final String FALLBACK_GENERATOR_VERSION = "dev";

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "json", "toml", "yml", "yaml", "txt", "xml", "properties");
    private static final Set<String> ALWAYS_TEXT_PATH_PREFIXES = Set.of(
            "tools/release/");

    private final String buildGeneratorVersion;

    public TemplateSubstitutionEngine() {
        this(loadBuildGeneratorVersion());
    }

    // Package-private — intended for tests that need a deterministic build-version source.
    TemplateSubstitutionEngine(String buildGeneratorVersion) {
        this.buildGeneratorVersion = buildGeneratorVersion;
    }

    /**
     * @return the generator version loaded from the build-time properties resource, or
     *         {@value #FALLBACK_GENERATOR_VERSION} if the resource is missing.
     */
    public String buildGeneratorVersion() {
        return buildGeneratorVersion;
    }

    /**
     * @return {@code true} when {@code entryName} should be treated as a text file and have
     *         template tokens substituted.
     */
    public boolean isTextEntry(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            return false;
        }
        for (String prefix : ALWAYS_TEXT_PATH_PREFIXES) {
            if (entryName.startsWith(prefix)) {
                return true;
            }
        }
        int dot = entryName.lastIndexOf('.');
        int slash = entryName.lastIndexOf('/');
        if (dot < 0 || dot < slash) {
            // No file extension and not in an always-text prefix → treat as binary.
            return false;
        }
        String ext = entryName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.contains(ext);
    }

    /**
     * Substitute template tokens in {@code source} using {@code context}.
     *
     * @param entryName the archive entry name (used to decide JSON-escaping)
     * @param source    the raw file content read from the archive
     * @param context   the substitution values
     * @return substituted bytes (UTF-8)
     */
    public byte[] substitute(String entryName, byte[] source, TemplateContext context) {
        String text = new String(source, StandardCharsets.UTF_8);
        boolean jsonEscape = entryName != null && entryName.toLowerCase(Locale.ROOT).endsWith(".json");
        Map<String, String> tokens = resolveTokens(context, jsonEscape);

        StringBuilder out = new StringBuilder(text.length() + 64);
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf("{{", i);
            if (open < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, open);
            int close = text.indexOf("}}", open + 2);
            if (close < 0) {
                // Unbalanced — leave the rest as-is.
                out.append(text, open, text.length());
                break;
            }
            String token = text.substring(open + 2, close);
            String replacement = tokens.get(token);
            if (replacement != null) {
                out.append(replacement);
            } else {
                // Unknown token — leave it as-is so downstream tests catch it.
                out.append(text, open, close + 2);
            }
            i = close + 2;
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, String> resolveTokens(TemplateContext context, boolean jsonEscape) {
        String generatorVersion = context.generatorVersion();
        if (BUILD_GENERATOR_VERSION.equals(generatorVersion)) {
            generatorVersion = buildGeneratorVersion;
        }
        String createdAt = DateTimeFormatter.ISO_INSTANT.format(context.createdAt());

        Map<String, String> tokens = new HashMap<>();
        tokens.put("REPO_NAME", maybeJsonEscape(context.repositoryName(), jsonEscape));
        tokens.put("REPO_OWNER", maybeJsonEscape(context.ownerName(), jsonEscape));
        tokens.put("PROJECT_DESCRIPTION", maybeJsonEscape(context.projectDescription(), jsonEscape));
        tokens.put("CREATED_AT", maybeJsonEscape(createdAt, jsonEscape));
        tokens.put("GENERATOR_VERSION", maybeJsonEscape(generatorVersion, jsonEscape));
        tokens.put("PROJECT_NAME", maybeJsonEscape(context.projectName(), jsonEscape));
        tokens.put("MAVEN_GROUP_ID", maybeJsonEscape(context.mavenGroupId(), jsonEscape));
        tokens.put("MAVEN_ARTIFACT_ID", maybeJsonEscape(context.mavenArtifactId(), jsonEscape));
        tokens.put("PYTHON_DISTRIBUTION_NAME", maybeJsonEscape(context.pythonDistributionName(), jsonEscape));
        tokens.put("PYTHON_IMPORT_NAME", maybeJsonEscape(context.pythonImportName(), jsonEscape));
        tokens.put("NPM_PACKAGE_NAME", maybeJsonEscape(context.npmPackageName(), jsonEscape));
        return tokens;
    }

    private static String maybeJsonEscape(String value, boolean jsonEscape) {
        if (!jsonEscape) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String loadBuildGeneratorVersion() {
        try (InputStream in = TemplateSubstitutionEngine.class.getClassLoader()
                .getResourceAsStream(BUILD_PROPERTIES_RESOURCE)) {
            if (in == null) {
                return FALLBACK_GENERATOR_VERSION;
            }
            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty(BUILD_PROPERTY_GENERATOR_VERSION);
            if (version == null || version.isBlank() || version.startsWith("${")) {
                return FALLBACK_GENERATOR_VERSION;
            }
            return version;
        } catch (IOException e) {
            return FALLBACK_GENERATOR_VERSION;
        }
    }

    /**
     * @return all token names this engine recognizes (used by tests).
     */
    static Set<String> recognizedTokens() {
        return new LinkedHashSet<>(Set.of(
                "REPO_NAME", "REPO_OWNER", "PROJECT_DESCRIPTION", "CREATED_AT", "GENERATOR_VERSION",
                "PROJECT_NAME", "MAVEN_GROUP_ID", "MAVEN_ARTIFACT_ID",
                "PYTHON_DISTRIBUTION_NAME", "PYTHON_IMPORT_NAME", "NPM_PACKAGE_NAME"));
    }
}
