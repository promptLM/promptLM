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

import dev.promptlm.repository.template.RepositoryTemplateExtractor;
import dev.promptlm.repository.template.TemplateContext;
import dev.promptlm.repository.template.TemplateSubstitutionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ZipFileRepositoryTemplateExtractor implements RepositoryTemplateExtractor {

    private static final Logger log = LoggerFactory.getLogger(ZipFileRepositoryTemplateExtractor.class);
    private static final String DEFAULT_TEMPLATE_ARCHIVE = "repo-template.zip";
    private static final String CONFIG_FILE_NAME = "promptlm.yml";

    /**
     * Schema version this loader knows how to read. A {@code promptlm.yml}
     * declaring a higher version is rejected; a missing version is treated as
     * {@value #SUPPORTED_SCHEMA_VERSION} with a deprecation warning so that
     * the field can be retrofitted to existing files non-disruptively. See
     * issue #353.
     */
    static final String SUPPORTED_SCHEMA_VERSION = "1";

    /**
     * Entries that are only relevant in Mode 2 (release-enabled). When
     * {@code release.enabled} is {@code false} in the generated repository's
     * {@value #CONFIG_FILE_NAME}, these files are skipped during extraction so
     * that the produced repository is a plain prompt-management store with no
     * CI/CD or build pipeline noise.
     *
     * @see <a href="https://github.com/promptLM/promptlm-app/issues/161">Issue #161</a>
     */
    private static final List<Pattern> RELEASE_ONLY_PATH_PATTERNS = List.of(
            Pattern.compile("^\\.github/.*"),
            Pattern.compile("^tools/.*"),
            Pattern.compile("^scripts/.*"),
            Pattern.compile("^pom\\.xml$"),
            Pattern.compile("^\\.promptlm/artifacts\\.toml$")
    );

    private final Resource templateArchive;
    private final TemplateSubstitutionEngine substitutionEngine;

    public ZipFileRepositoryTemplateExtractor() {
        this(new ClassPathResource(DEFAULT_TEMPLATE_ARCHIVE), new TemplateSubstitutionEngine());
    }

    ZipFileRepositoryTemplateExtractor(Resource templateArchive) {
        this(templateArchive, new TemplateSubstitutionEngine());
    }

    ZipFileRepositoryTemplateExtractor(Resource templateArchive, TemplateSubstitutionEngine substitutionEngine) {
        this.templateArchive = templateArchive;
        this.substitutionEngine = substitutionEngine;
    }

    /**
     * Path prefixes/suffixes whose entries must be made executable in the extracted
     * working tree. These are the only files in the shipped template that the generated
     * CI workflows invoke as scripts (e.g. {@code ./tools/release/build-artifacts}); if
     * any of them lands without the executable bit the workflow fails with a confusing
     * "Permission denied" error.
     *
     * <p>This list is the source of truth for the regression guard added in issue #323 —
     * if a new shell script is added to the template, register it here too.
     */
    private static final List<Pattern> EXECUTABLE_PATH_PATTERNS = List.of(
            Pattern.compile("^tools/release/[^/]+$"),
            Pattern.compile("^scripts/[^/]+\\.sh$")
    );

    @Override
    public void extractTo(Path repoPath, TemplateContext context) {
        Objects.requireNonNull(context, "context");

        Map<String, byte[]> entries = readAllEntries();
        byte[] configBytes = entries.get(CONFIG_FILE_NAME);
        verifySchemaVersion(configBytes);
        boolean releaseEnabled = isReleaseEnabled(configBytes);
        log.debug("Repository template release.enabled resolved to {}", releaseEnabled);

        boolean posixSupported = FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix");

        try {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                String name = entry.getKey();
                if (!releaseEnabled && isReleaseOnly(name)) {
                    log.debug("Skipping release-only template entry (release.enabled=false): {}", name);
                    continue;
                }
                Path targetPath = repoPath.resolve(name);
                Files.createDirectories(targetPath.getParent());
                byte[] payload = entry.getValue();
                if (substitutionEngine.isTextEntry(name)) {
                    payload = substitutionEngine.substitute(name, payload, context);
                }
                Files.write(targetPath, payload);
                if (posixSupported) {
                    applyPosixMode(targetPath, name);
                }
            }
            log.debug("Successfully extracted repository template to: {}", repoPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract repository template", e);
        }
    }

    private Map<String, byte[]> readAllEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream zipInputStream = templateArchive.getInputStream();
             ZipInputStream zis = new ZipInputStream(zipInputStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                zis.transferTo(buffer);
                entries.put(entry.getName(), buffer.toByteArray());
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read repository template archive", e);
        }
        return entries;
    }

    /**
     * Apply a sensible POSIX mode to the extracted file. We cannot reliably read the
     * mode from the {@code java.util.zip.ZipInputStream} API in JDK 21 (the external
     * attributes field of {@link ZipEntry} is package-private and only populated when
     * reading via {@code ZipFile} random-access), so we use a deterministic
     * path-pattern heuristic instead: entries under {@code tools/release/} and
     * {@code scripts/*.sh} get {@code 0755}; everything else gets {@code 0644}. This
     * mirrors the {@code fileMode} pinning in {@code src/assembly/repo-template.xml}
     * which is the source of truth for the shipped archive.
     *
     * <p>Failures to apply the mode are logged at debug and never propagated — losing
     * the executable bit is a workflow regression, not a reason to abort the rollout.
     */
    static void applyPosixMode(Path targetPath, String entryName) {
        int mode = isExecutableEntry(entryName) ? 0755 : 0644;
        try {
            Files.setPosixFilePermissions(targetPath, fromMode(mode));
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("Failed to apply POSIX mode {} to {}: {}",
                    Integer.toOctalString(mode), targetPath, e.getMessage());
        }
    }

    static boolean isExecutableEntry(String entryName) {
        if (entryName == null) {
            return false;
        }
        String normalized = entryName.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (Pattern p : EXECUTABLE_PATH_PATTERNS) {
            if (p.matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    static Set<PosixFilePermission> fromMode(int mode) {
        EnumSet<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
        if ((mode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
        if ((mode & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
        if ((mode & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
        return perms;
    }

    private static boolean isReleaseOnly(String entryName) {
        for (Pattern pattern : RELEASE_ONLY_PATH_PATTERNS) {
            if (pattern.matcher(entryName).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Verify the {@code schemaVersion} declared in the template's
     * {@code promptlm.yml}. A missing version is tolerated with a
     * deprecation warning (treated as {@value #SUPPORTED_SCHEMA_VERSION});
     * a higher version is rejected with {@link IllegalStateException} so
     * the rollout fails loudly rather than silently producing a malformed
     * repository.
     *
     * <p>Like {@link #isReleaseEnabled(byte[])} this uses a regex over the
     * top-level key rather than a full YAML parser to keep the module
     * dependency-free. See issue #353.
     *
     * @param configBytes the raw bytes of {@code promptlm.yml}, or
     *                    {@code null} / empty if the file is absent (in
     *                    which case the check is skipped — the caller's
     *                    fallback path covers that case).
     */
    static void verifySchemaVersion(byte[] configBytes) {
        if (configBytes == null || configBytes.length == 0) {
            return;
        }
        String declared = readSchemaVersion(configBytes);
        if (declared == null) {
            log.warn("{} is missing 'schemaVersion'; assuming '{}'. This will become an error in a future release.",
                    CONFIG_FILE_NAME, SUPPORTED_SCHEMA_VERSION);
            return;
        }
        if (SUPPORTED_SCHEMA_VERSION.equals(declared)) {
            return;
        }
        if (declared.compareTo(SUPPORTED_SCHEMA_VERSION) > 0) {
            throw new IllegalStateException(
                    "Unsupported %s schemaVersion '%s'; this build of promptLM only understands schemaVersion '%s'. Upgrade promptLM to consume this template."
                            .formatted(CONFIG_FILE_NAME, declared, SUPPORTED_SCHEMA_VERSION));
        }
        log.warn("{} declares older schemaVersion '{}'; loader expects '{}'. Proceeding on a best-effort basis.",
                CONFIG_FILE_NAME, declared, SUPPORTED_SCHEMA_VERSION);
    }

    private static String readSchemaVersion(byte[] configBytes) {
        String yaml = new String(configBytes, StandardCharsets.UTF_8);
        Matcher matcher = Pattern
                .compile("(?m)^schemaVersion:\\s*['\"]?([^'\"#\\s]+)['\"]?\\s*(?:#.*)?$")
                .matcher(yaml);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Parse {@code release.enabled} from the template's {@code promptlm.yml}.
     * Defaults to {@code false} when the file is missing or the flag cannot be
     * read so that the safer Mode 1 (prompt management) layout is produced.
     *
     * <p>The parsing intentionally avoids pulling in a full YAML library here:
     * only one flag is consulted and it sits at a fixed location in the
     * template. A regex over the indented {@code release.enabled} key is
     * sufficient and keeps this low-level component dependency-free.
     */
    static boolean isReleaseEnabled(byte[] configBytes) {
        if (configBytes == null || configBytes.length == 0) {
            return false;
        }
        String yaml = new String(configBytes, StandardCharsets.UTF_8);
        Matcher matcher = Pattern
                .compile("(?m)^\\s+enabled:\\s*(true|false)\\b")
                .matcher(stripCommentsAndIsolateReleaseBlock(yaml));
        if (matcher.find()) {
            return Boolean.parseBoolean(matcher.group(1));
        }
        return false;
    }

    private static String stripCommentsAndIsolateReleaseBlock(String yaml) {
        List<String> lines = new ArrayList<>();
        boolean inReleaseBlock = false;
        for (String rawLine : yaml.split("\\R", -1)) {
            String line = stripInlineComment(rawLine);
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (line.startsWith("release:")) {
                inReleaseBlock = true;
                continue;
            }
            if (inReleaseBlock) {
                if (!Character.isWhitespace(line.charAt(0))) {
                    break;
                }
                lines.add(line);
            }
        }
        return String.join("\n", lines);
    }

    private static String stripInlineComment(String line) {
        int hashIndex = line.indexOf('#');
        if (hashIndex < 0) {
            return line;
        }
        return line.substring(0, hashIndex);
    }
}
