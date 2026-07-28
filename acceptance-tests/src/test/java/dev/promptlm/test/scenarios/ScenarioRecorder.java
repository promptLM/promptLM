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

package dev.promptlm.test.scenarios;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Append-only narrative recorder. Each {@link #step(String)} call writes a
 * timestamped bullet to {@code target/diagnostics/<test>/narrative.md} so
 * post-mortems can replay the test's user-shaped story.
 */
public final class ScenarioRecorder {

    private final Path narrativeFile;

    private ScenarioRecorder(Path narrativeFile) {
        this.narrativeFile = narrativeFile;
    }

    /**
     * Creates a recorder writing under {@code target/diagnostics/<test>/}.
     */
    public static ScenarioRecorder forTest(String testName) {
        Path dir = Path.of("target", "diagnostics", testName).toAbsolutePath();
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("narrative.md");
            if (!Files.exists(file)) {
                Files.writeString(file, "# Scenario narrative — " + testName + "\n\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
            return new ScenarioRecorder(file);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Appends a single step bullet to the narrative.
     */
    public void step(String description) {
        String line = "- " + Instant.now() + " — " + description + "\n";
        try {
            Files.writeString(narrativeFile, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
