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

package dev.promptlm.test.harness;

import dev.promptlm.test.support.NativeBinaryLauncher;
import dev.promptlm.test.support.NativeBinaryLauncher.CommandResult;
import dev.promptlm.test.support.NativeBinaryLauncher.RunningProcess;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Handle exposed to tests for driving native promptLM binaries.
 *
 * <p>Two concrete variants:
 * <ul>
 *     <li>{@link CliHandle} — a stateless reference to the CLI binary plus the
 *         per-test user-home + workspace directories. Tests invoke
 *         {@link CliHandle#exec(String...)} to run a CLI command end-to-end.</li>
 *     <li>{@link WebappHandle} — the long-running native webapp process, the
 *         port it bound to, and a {@link StudioDriver} for clicking through it
 *         in Playwright.</li>
 * </ul>
 *
 * <p>Deliberately omits any HTTP-client / API-client accessor. All UI verbs
 * go through the studio driver; all CLI verbs through {@code exec}. Witnesses
 * (workspace state, Gitea contents, Artifactory contents, LLM stub) live
 * outside the handle.
 */
public sealed interface NativeAppHandle permits NativeAppHandle.CliHandle, NativeAppHandle.WebappHandle {

    /**
     * Handle that drives the native CLI by shell-executing each command.
     */
    record CliHandle(Path binaryPath,
                     Path userHome,
                     Path workspace,
                     Map<String, String> sysProps,
                     Duration commandTimeout) implements NativeAppHandle {

        /**
         * Runs the CLI binary synchronously with the given arguments and
         * returns the captured exit code + stdout.
         */
        public CommandResult exec(String... args) {
            return NativeBinaryLauncher.runCliCommand(
                    userHome, sysProps, List.of(args), commandTimeout, workspace);
        }

        /**
         * Spawns the CLI binary asynchronously (for long-running commands
         * such as {@code studio}). Callers own the returned process and must
         * destroy it.
         */
        public RunningProcess execAsync(String... args) {
            return NativeBinaryLauncher.startCliCommand(
                    userHome, sysProps, Arrays.asList(args), workspace);
        }
    }

    /**
     * Handle that drives the native webapp through Playwright.
     */
    record WebappHandle(Path binaryPath,
                        Path userHome,
                        int port,
                        String baseUrl,
                        StudioDriver studio,
                        RunningProcess process,
                        Restart restartHook) implements NativeAppHandle {

        /**
         * Tears down the underlying process and reboots it on the same port
         * with the same user-home / system properties.
         *
         * <p>Used by restart-resilience scenarios. Returns the fresh handle
         * the fixture installed during the reboot.
         */
        public WebappHandle restart() {
            return restartHook.restart();
        }

        /**
         * Strategy interface implemented by the fixture so the handle can ask
         * to be restarted without holding a back-reference to the extension.
         */
        @FunctionalInterface
        public interface Restart {
            WebappHandle restart();
        }
    }
}
