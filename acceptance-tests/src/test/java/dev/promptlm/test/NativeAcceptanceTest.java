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

package dev.promptlm.test;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker for end-to-end native-binary acceptance scenarios.
 *
 * <p>Tests carrying this tag drive the native CLI and webapp binaries
 * exclusively through user-shaped verbs (Playwright clicks, CLI shell-exec),
 * and verify outcomes via witness layers (workspace state, Gitea REST,
 * Artifactory REST). API endpoints are never used as drivers.
 *
 * <p>Selected by surefire when {@code -Dpromptlm.test.groups=native-acceptance}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("native-acceptance")
public @interface NativeAcceptanceTest {
}
