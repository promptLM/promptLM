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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.promptlm.domain.ObjectMapperFactory;
import dev.promptlm.test.support.GiteaRepositoryHelper;
import dev.promptlm.testutils.gitea.Gitea;
import dev.promptlm.testutils.gitea.GiteaContainer;
import dev.promptlm.testutils.gitea.WithGitea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Pins the invariant of issue #352: a freshly created PromptSpec (no response
 * attached) must be persisted locally but must NOT be committed or pushed to the
 * remote git repository. Only once a response is attached (e.g. via execute)
 * does the spec get pushed.
 */
@WithGitea(actionsEnabled = false)
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SaveWithoutResponseContractTest {

    private static final ObjectMapper JSON_MAPPER = ObjectMapperFactory.createJsonMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private GiteaContainer gitea;
    private Path userHome;
    private Path workspaceRoot;
    private String baseUrl;

    @BeforeAll
    void setUp(@TempDir Path tempDir, @Gitea GiteaContainer giteaContainer) throws IOException {
        this.gitea = giteaContainer;
        this.userHome = tempDir.resolve("save-without-response-home");
        this.workspaceRoot = userHome.resolve("workspace");
        Files.createDirectories(workspaceRoot);
        this.baseUrl = TestApplicationManager.startApplicationWithGitea(
                userHome,
                gitea.getWebUrl(),
                gitea.getAdminUsername(),
                gitea.getAdminToken()
        );
    }

    @AfterAll
    void tearDown() {
        TestApplicationManager.stopApplication();
    }

    /**
     * Creating a prompt without an attached response must persist it locally
     * (retrievable via GET) but must NOT push the prompt YAML to the remote
     * development branch.
     */
    @Test
    @DisplayName("create without response: persists locally, does not push to remote")
    void createPromptWithoutResponse_doesNotPushToRemote() throws Exception {
        JsonNode project = createStore(uniqueRepoName("draft"));
        String group = "support";
        String name = uniquePromptName("draft");

        JsonNode created = createPrompt(project, group, name, "No response yet.");
        String promptId = created.path("id").asText();
        assertThat(promptId).isNotBlank();
        assertThat(created.path("response").isMissingNode() || created.path("response").isNull())
                .as("freshly created prompt must not carry a response")
                .isTrue();

        // Local persistence works: GET succeeds.
        JsonNode fetched = getJson("/api/prompts/" + promptId);
        assertThat(fetched.path("id").asText()).isEqualTo(promptId);

        // The remote development branch must NOT contain the prompt YAML.
        // Sustain the absence assertion for the same window the affirmative
        // BackendFacadeInfraE2eTest grants pushes to land (60s polled at 2s),
        // condensed to 5s of continuous checks to keep this suite fast.
        String repoUrl = project.path("repositoryUrl").asText();
        String remoteRelativePath = "prompts/%s/%s/promptlm.yml".formatted(group, name);
        await()
                .atMost(Duration.ofSeconds(7))
                .pollInterval(Duration.ofMillis(500))
                .during(Duration.ofSeconds(5))
                .until(() -> GiteaRepositoryHelper.fetchRawFile(
                                HTTP_CLIENT,
                                repoUrl,
                                "development",
                                remoteRelativePath,
                                gitea.getAdminToken()
                        ),
                        Optional::isEmpty);
    }

    /**
     * Attaching a response (via execute) to a previously-created draft must
     * trigger the push so the YAML appears on the remote development branch.
     *
     * <p>This requires a real LLM call (OPENAI_API_KEY in the environment) —
     * same constraint as HappyPathUserJourneyTest#runPromptPersistsManualExecution.
     */
    @Test
    @DisplayName("execute after create: attaches response and pushes YAML to remote")
    void updatePromptAttachesResponse_thenPushesToRemote() throws Exception {
        JsonNode project = createStore(uniqueRepoName("execute"));
        String group = "support";
        String name = uniquePromptName("execute");

        JsonNode created = createPrompt(project, group, name, "Say hello briefly.");
        String promptId = created.path("id").asText();

        // Sanity: not yet pushed.
        String repoUrl = project.path("repositoryUrl").asText();
        String remoteRelativePath = "prompts/%s/%s/promptlm.yml".formatted(group, name);
        assertThat(GiteaRepositoryHelper.fetchRawFile(
                HTTP_CLIENT, repoUrl, "development", remoteRelativePath, gitea.getAdminToken()))
                .as("prompt must not be on remote before a response is attached")
                .isEmpty();

        // Execute the stored prompt — body-less POST triggers a clean run that
        // records a MANUAL Execution (with response) against the stored spec.
        // See PromptSpecController#executeStoredPrompt.
        HttpResponse<String> executeResponse = sendRequest(
                "POST", "/api/prompts/" + promptId + "/execute", null);
        assertThat(executeResponse.statusCode())
                .as("execute must succeed (requires OPENAI_API_KEY); body=%s", executeResponse.body())
                .isEqualTo(200);
        JsonNode executed = JSON_MAPPER.readTree(executeResponse.body());
        assertThat(executed.path("response").isObject() || executed.path("response").isTextual())
                .as("execute response must carry a response object")
                .isTrue();

        // Now the spec MUST be present on the remote development branch.
        Optional<String> remoteYaml = await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> GiteaRepositoryHelper.fetchRawFile(
                                HTTP_CLIENT,
                                repoUrl,
                                "development",
                                remoteRelativePath,
                                gitea.getAdminToken()
                        ),
                        Optional::isPresent);
        assertThat(remoteYaml).isPresent();
    }

    private JsonNode createStore(String repoName) throws Exception {
        ObjectNode request = JSON_MAPPER.createObjectNode();
        request.put("repoDir", workspaceRoot.toString());
        request.put("repoName", repoName);
        request.put("repoGroup", gitea.getAdminUsername());
        request.put("description", "save-without-response contract test");

        HttpResponse<String> response = sendRequest("POST", "/api/store", request);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON_MAPPER.readTree(response.body());
    }

    private JsonNode createPrompt(JsonNode project, String group, String name, String userMessage) throws Exception {
        HttpResponse<String> response = sendRequest(
                "POST", "/api/prompts", buildPromptRequest(project, group, name, userMessage));
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON_MAPPER.readTree(response.body());
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = sendRequest("GET", path, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON_MAPPER.readTree(response.body());
    }

    private HttpResponse<String> sendRequest(String method, String path, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(120));

        if (body == null) {
            if ("GET".equals(method)) {
                builder.GET();
            } else if ("PUT".equals(method)) {
                builder.PUT(HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        }

        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private ObjectNode buildPromptRequest(JsonNode project, String group, String name, String userMessage) {
        ObjectNode request = JSON_MAPPER.createObjectNode();
        request.put("group", group);
        request.put("name", name);
        request.put("description", "save-without-response contract");
        request.put("version", "1.0.0-SNAPSHOT");
        request.put("repositoryUrl", project.path("repositoryUrl").asText());
        request.put("placeholderStartPattern", "{{");
        request.put("placeholderEndPattern", "}}");

        ObjectNode extensionRoot = request.putObject("extensions").putObject("x-e2e");
        extensionRoot.put("suite", "save-without-response");
        extensionRoot.put("runId", UUID.randomUUID().toString());

        ObjectNode requestBody = request.putObject("request");
        requestBody.put("type", "chat/completion");
        requestBody.put("vendor", "openai");
        requestBody.put("model", "gpt-4o-mini");
        requestBody.put("url", "https://api.openai.com/v1/chat/completions");

        ObjectNode params = requestBody.putObject("parameters");
        params.put("temperature", 0.0d);
        params.put("maxTokens", 16);
        params.put("stream", false);

        ArrayNode messages = requestBody.putArray("messages");
        messages.addObject()
                .put("role", "SYSTEM")
                .put("content", "You are a brief assistant.");
        messages.addObject()
                .put("role", "USER")
                .put("content", userMessage);

        return request;
    }

    private String uniqueRepoName(String prefix) {
        return "save-without-response-%s-%s".formatted(prefix, System.nanoTime());
    }

    private String uniquePromptName(String prefix) {
        return "%s-%s".formatted(prefix, System.nanoTime());
    }
}
