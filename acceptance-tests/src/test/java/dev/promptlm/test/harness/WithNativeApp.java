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

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a test class drives the native promptLM binaries.
 *
 * <p>The {@link NativeBinaryFixture} extension wires in:
 * <ul>
 *     <li>A skip / hard-fail condition when the required binaries are missing
 *         (controlled by {@code -Dpromptlm.test.requireNativeBinaries=true}).</li>
 *     <li>Optional infrastructure containers (Gitea, Artifactory).</li>
 *     <li>Optional in-process WireMock-based LLM stub.</li>
 *     <li>Per-test fresh {@code userHome} / workspace directories.</li>
 *     <li>A {@link NativeAppHandle} parameter resolver.</li>
 * </ul>
 *
 * <p>Selectors for which binaries to launch are declared per class via
 * {@link #binaries()}. The fixture starts them lazily in {@code @BeforeAll}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(NativeBinaryFixture.class)
public @interface WithNativeApp {

    /**
     * Which native binaries the test needs.
     */
    Binary[] binaries() default { Binary.WEBAPP };

    /**
     * If {@code true} the fixture starts a Gitea container and stamps the
     * remote-store properties on every launched binary.
     */
    boolean withGitea() default true;

    /**
     * If {@code true} the fixture starts an Artifactory container and exposes
     * its base URL through {@link NativeAppHandle}-attached system properties.
     */
    boolean withArtifactory() default false;

    /**
     * If {@code true} the fixture starts the {@code LlmStubServer} on a random
     * port and points the webapp's Spring AI OpenAI client at it.
     */
    boolean withLlmStub() default false;

    /**
     * Native binary selector.
     */
    enum Binary {
        CLI,
        WEBAPP
    }
}
