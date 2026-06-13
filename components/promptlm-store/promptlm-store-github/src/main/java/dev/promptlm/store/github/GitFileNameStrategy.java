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

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
class GitFileNameStrategy {

    private static final Pattern SAFE_SEGMENT_PATTERN = Pattern.compile("[a-z0-9._-]+");

    String buildPromptPath(String group, String name) {
        String normalizedGroup = normalizeSegment(group, "group");
        String normalizedName = normalizeSegment(name, "name");

        return Path.of("prompts")
                .resolve(normalizedGroup)
                .resolve(normalizedName)
                .resolve("promptlm.yml")
                .toString();
    }

    private String normalizeSegment(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or empty");
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("/") || normalized.contains("\\") || normalized.contains("..")) {
            throw new IllegalArgumentException(fieldName + " contains invalid path segment");
        }
        if (!SAFE_SEGMENT_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " contains unsupported characters");
        }

        return normalized;
    }

}
