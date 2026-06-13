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

package dev.promptlm.execution.litellm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "promptlm.gateway.litellm")
public class LiteLlmGatewayProperties {

    private static final String DEFAULT_IMAGE_REPOSITORY = "ghcr.io/berriai/litellm";

    /**
     * Enables the LiteLLM gateway.
     */
    private boolean enabled = false;

    /**
     * LiteLLM Docker image version tag. Used to compose the default image reference
     * ({@code ghcr.io/berriai/litellm:<version>}). Ignored when {@code docker.image}
     * is set explicitly.
     */
    private String version = "v1.82.3.dev.6";

    /**
     * Base URL where LiteLLM is exposed.
     * When unset, derived from {@code docker.port} as {@code http://localhost:<docker.port>}
     * so the two settings stay in sync. Override explicitly in production.
     */
    private String baseUrl;

    /**
     * Prompt vendor identifier handled by LiteLLM.
     */
    private String vendor = "litellm";

    /**
     * Mapping between PromptLM model identifiers and LiteLLM routes.
     */
    private Map<String, String> modelAliases = new HashMap<>();

    /**
     * Additional environment variables supplied to the LiteLLM container.
     */
    private Map<String, String> environment = new HashMap<>();

    @NestedConfigurationProperty
    private final Docker docker = new Docker();

    @NestedConfigurationProperty
    private final Discovery discovery = new Discovery();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl != null ? baseUrl : "http://localhost:" + docker.getPort();
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public Map<String, String> getModelAliases() {
        return modelAliases;
    }

    public void setModelAliases(Map<String, String> modelAliases) {
        this.modelAliases = modelAliases;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public void setEnvironment(Map<String, String> environment) {
        this.environment = environment;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Docker getDocker() {
        return docker;
    }

    public Discovery getDiscovery() {
        return discovery;
    }

    /**
     * Resolves the effective Docker image reference. Returns {@code docker.image} when
     * set explicitly, otherwise composes the image from the default repository and
     * {@link #version}.
     */
    public String resolveImage() {
        String explicitImage = docker.getImage();
        if (explicitImage != null && !explicitImage.isBlank()) {
            return explicitImage;
        }
        if (version != null && !version.isBlank()) {
            return DEFAULT_IMAGE_REPOSITORY + ":" + version;
        }
        return null;
    }

    public static final class Docker {

        /**
         * Whether the module should attempt to manage a LiteLLM container.
         */
        private boolean manage = true;

        /**
         * LiteLLM container image (must be immutable tag or digest, no :latest).
         */
        private String image;

        /**
         * Container name used when spawning LiteLLM.
         */
        private String containerName = "promptlm-litellm";

        /**
         * Host port exposed by LiteLLM.
         */
        private int port = 4000;

        /**
         * Optional Docker network to attach to.
         */
        private String network;

        /**
         * Timeout waiting for LiteLLM readiness.
         */
        private Duration readinessTimeout = Duration.ofSeconds(30);

        /**
         * Path queried for readiness checks.
         */
        private String readinessPath = "/health";

        public boolean isManage() {
            return manage;
        }

        public void setManage(boolean manage) {
            this.manage = manage;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public String getContainerName() {
            return containerName;
        }

        public void setContainerName(String containerName) {
            this.containerName = containerName;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getNetwork() {
            return network;
        }

        public void setNetwork(String network) {
            this.network = network;
        }

        public Duration getReadinessTimeout() {
            return readinessTimeout;
        }

        public void setReadinessTimeout(Duration readinessTimeout) {
            this.readinessTimeout = readinessTimeout;
        }

        public String getReadinessPath() {
            return readinessPath;
        }

        public void setReadinessPath(String readinessPath) {
            this.readinessPath = readinessPath;
        }
    }

    public static final class Discovery {

        /**
         * Enables LiteLLM model discovery via the HTTP API.
         */
        private boolean enabled = false;

        /**
         * Cache duration for discovered model lists.
         */
        private Duration cacheTtl = Duration.ofMinutes(5);

        /**
         * Path queried to list available models.
         */
        private String modelsPath = "/v1/models";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }

        public String getModelsPath() {
            return modelsPath;
        }

        public void setModelsPath(String modelsPath) {
            this.modelsPath = modelsPath;
        }
    }
}
