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
import dev.promptlm.domain.promptspec.PromptSpec;
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

@WithGitea(actionsEnabled = false)
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackendFacadeInfraE2eTest {

    private static final ObjectMapper JSON_MAPPER = ObjectMapperFactory.createJsonMapper();
    private static final ObjectMapper YAML_MAPPER = ObjectMapperFactory.createYamlMapper();
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
        this.userHome = tempDir.resolve("backend-facade-home");
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
     * Verifies API store creation persists active project state in user context and repository metadata on disk.
     */
    @Test
    @DisplayName("persists activeProject context and repository metadata when store is created via api")
    void shouldPersistActiveProjectContextAndRepositoryMetadataWhenStoreIsCreatedViaApi() throws Exception {
        JsonNode project = createStore(uniqueRepoName("ctx"));
        String projectId = project.path("id").asText();
        String repositoryUrl = project.path("repositoryUrl").asText();
        Path localPath = Path.of(project.path("localPath").asText());

        assertThat(projectId).isNotBlank();
        assertThat(repositoryUrl).isNotBlank();
        assertThat(localPath).isDirectory();

        Path contextFile = userHome.resolve(".promptlm/context.json");
        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    assertThat(contextFile).isRegularFile();
                    JsonNode context = JSON_MAPPER.readTree(Files.readString(contextFile));
                    assertThat(context.path("projects").isArray()).isTrue();
                    // context.json stores activeProject as an id-only reference; full metadata lives in projects[].
                    assertThat(context.path("activeProject").path("id").asText()).isEqualTo(projectId);
                    JsonNode activeProjectEntry = findProjectById(context.path("projects"), projectId);
                    assertThat(activeProjectEntry).as("activeProject entry in projects[]").isNotNull();
                    assertThat(activeProjectEntry.path("localPath").asText()).isEqualTo(localPath.toString());
                    assertThat(activeProjectEntry.path("repositoryUrl").asText()).isEqualTo(repositoryUrl);
                });

        Path metadataFile = localPath.resolve(".promptlm/metadata.json");
        assertThat(metadataFile).isRegularFile();

        JsonNode metadata = JSON_MAPPER.readTree(Files.readString(metadataFile));
        assertThat(metadata.path("repository").isObject()).isTrue();
        assertThat(metadata.path("prompts").isArray()).isTrue();
        assertThat(metadata.path("metadata").isObject()).isTrue();
        assertThat(metadata.path("metadata").path("format_version").asText()).isNotBlank();
    }

    /**
     * Verifies prompt creation persists PromptSpec contract fields and prompt file in local and remote development
     * branch.
     *
     * <p>Per the {@code #352} deferred-push save flow, the remote write happens only after the LLM response is
     * attached. The local commit appears immediately on save; the remote commit is amended-and-pushed by
     * {@code PromptPushListener} on {@code PromptExecutedEvent}. This test therefore asserts the {@code #352}
     * invariant: anything reaching the remote must carry a response — no orphan-response YAML is ever pushed.
     */
    @Test
    @DisplayName("creates prompt spec and persists prompt file locally and in remote development branch after execution")
    void shouldCreatePromptSpecAndPersistPromptFileLocallyAndInRemoteDevelopmentBranch() throws Exception {
        JsonNode project = createStore(uniqueRepoName("create"));
        String group = "support";
        String name = uniquePromptName("welcome");

        JsonNode created = createPrompt(project, group, name, "Help {{customer_name}} politely.");
        String promptId = created.path("id").asText();
        String semanticHash = created.path("semanticHash").asText();

        assertThat(promptId).isNotBlank();
        assertThat(created.path("group").asText()).isEqualTo(group);
        assertThat(created.path("name").asText()).isEqualTo(name);
        assertThat(created.path("request").path("vendor").asText()).isEqualTo("openai");
        assertThat(created.path("request").path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(created.path("request").path("parameters").path("temperature").asDouble()).isEqualTo(0.35d);
        assertThat(created.path("placeholders").path("startPattern").asText()).isEqualTo("{{");
        assertThat(created.path("placeholders").path("endPattern").asText()).isEqualTo("}}");
        assertThat(created.path("extensions").path("x-e2e").path("suite").asText()).isEqualTo("backend-facade-infra");
        assertThat(semanticHash).isNotBlank();

        Path localRepo = Path.of(project.path("localPath").asText());
        Path promptFile = localRepo.resolve("prompts").resolve(group).resolve(name).resolve("promptlm.yml");
        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(promptFile).isRegularFile());

        PromptSpec localSpec = YAML_MAPPER.readValue(Files.readString(promptFile), PromptSpec.class);
        assertThat(localSpec.getId()).isEqualTo(promptId);
        assertThat(localSpec.getGroup()).isEqualTo(group);
        assertThat(localSpec.getName()).isEqualTo(name);

        // Remote write is deferred until PromptExecutedEvent → amend + push (see #352).
        String remoteRelativePath = "prompts/%s/%s/promptlm.yml".formatted(group, name);
        Optional<String> remoteYaml = awaitRemoteFile(project.path("repositoryUrl").asText(), remoteRelativePath);
        assertThat(remoteYaml).isPresent();

        PromptSpec remoteSpec = YAML_MAPPER.readValue(remoteYaml.orElseThrow(), PromptSpec.class);
        assertThat(remoteSpec.getId()).isEqualTo(promptId);
        assertThat(remoteSpec.getGroup()).isEqualTo(group);
        assertThat(remoteSpec.getName()).isEqualTo(name);
        // Invariant from #352: anything that reaches the remote must carry a response.
        assertThat(remoteSpec.getResponse())
                .as("remote spec must carry an attached response — no orphan-response YAML reaches the remote")
                .isNotNull();
    }

    /**
     * Verifies semantic prompt updates round-trip through API and increment revision with a changed semantic hash.
     */
    @Test
    @DisplayName("increments revision and changes semantic hash when prompt semantics change")
    void shouldIncrementRevisionAndChangeSemanticHashWhenPromptSemanticsChange() throws Exception {
        JsonNode project = createStore(uniqueRepoName("update"));
        String group = "support";
        String name = uniquePromptName("rev");

        JsonNode created = createPrompt(project, group, name, "Original response guidance.");
        String promptId = created.path("id").asText();
        int oldRevision = created.path("revision").asInt();
        String oldHash = created.path("semanticHash").asText();

        JsonNode updated = updatePrompt(project, promptId, group, name, "Updated response guidance with new policy.");
        assertThat(updated.path("id").asText()).isEqualTo(promptId);
        assertThat(updated.path("revision").asInt()).isGreaterThan(oldRevision);
        assertThat(updated.path("semanticHash").asText()).isNotEqualTo(oldHash);

        JsonNode fetched = getJson("/api/prompts/" + promptId);
        assertThat(fetched.path("id").asText()).isEqualTo(promptId);
        assertThat(fetched.path("revision").asInt()).isEqualTo(updated.path("revision").asInt());
        assertThat(fetched.path("semanticHash").asText()).isEqualTo(updated.path("semanticHash").asText());
    }

    /**
     * Verifies duplicate prompt creation for the same group/name returns conflict.
     */
    @Test
    @DisplayName("returns conflict when prompt with same group and name already exists")
    void shouldReturnConflictWhenPromptWithSameGroupAndNameAlreadyExists() throws Exception {
        JsonNode project = createStore(uniqueRepoName("dup"));
        String group = "support";
        String name = uniquePromptName("duplicate");

        createPrompt(project, group, name, "First prompt payload.");
        HttpResponse<String> duplicateResponse = postJson("/api/prompts", buildPromptRequest(project, group, name, "Second prompt payload.", null));

        assertThat(duplicateResponse.statusCode()).isEqualTo(409);
    }

    /**
     * Verifies retiring a prompt updates API state and persisted YAML retirement fields.
     */
    @Test
    @DisplayName("retires prompt and exposes retirement fields in api and persisted yaml")
    void shouldRetirePromptAndExposeRetiredFieldsInApiAndPersistedYaml() throws Exception {
        JsonNode project = createStore(uniqueRepoName("retire"));
        String group = "support";
        String name = uniquePromptName("retire-target");
        String reason = "No longer used by active workflows";

        JsonNode created = createPrompt(project, group, name, "Retire me.");
        String promptId = created.path("id").asText();

        HttpResponse<String> retireResponse = putJson("/api/prompts/" + promptId + "/retire?reason=" + encode(reason), null);
        assertThat(retireResponse.statusCode()).isEqualTo(200);
        JsonNode retired = JSON_MAPPER.readTree(retireResponse.body());
        assertThat(retired.path("id").asText()).isEqualTo(promptId);
        assertThat(retired.path("status").asText()).isEqualTo("RETIRED");
        assertThat(retired.path("retiredReason").asText()).isEqualTo(reason);
        assertThat(retired.path("retiredAt").asText()).isNotBlank();

        Path promptFile = Path.of(project.path("localPath").asText())
                .resolve("prompts")
                .resolve(group)
                .resolve(name)
                .resolve("promptlm.yml");
        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    PromptSpec persisted = YAML_MAPPER.readValue(Files.readString(promptFile), PromptSpec.class);
                    assertThat(persisted.getStatus()).isEqualTo(PromptSpec.PromptStatus.RETIRED);
                    assertThat(persisted.getRetiredReason()).isEqualTo(reason);
                    assertThat(persisted.getRetiredAt()).isNotNull();
                });
    }

    /**
     * Verifies stats endpoint reports active project metadata and prompt group counts from the current store.
     */
    @Test
    @DisplayName("reports prompt stats with active project git metadata")
    void shouldReportPromptStatsWithActiveProjectGitMetadata() throws Exception {
        JsonNode project = createStore(uniqueRepoName("stats"));
        String group = "support";
        createPrompt(project, group, uniquePromptName("stats"), "Stats payload.");

        JsonNode stats = getJson("/api/prompts/stats");
        assertThat(stats.path("totalPrompts").asInt()).isGreaterThan(0);
        assertThat(stats.path("activeProjects").asInt()).isGreaterThan(0);
        assertThat(stats.path("countByGroup").path(group).asLong()).isGreaterThan(0L);
        assertThat(stats.path("lastUpdated").asText()).isNotBlank();
    }

    private JsonNode createStore(String repoName) throws Exception {
        ObjectNode request = JSON_MAPPER.createObjectNode();
        request.put("repoDir", workspaceRoot.toString());
        request.put("repoName", repoName);
        request.put("repoGroup", gitea.getAdminUsername());
        request.put("description", "backend facade infra e2e");

        HttpResponse<String> response = postJson("/api/store", request);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON_MAPPER.readTree(response.body());
    }

    private JsonNode createPrompt(JsonNode project, String group, String name, String userMessage) throws Exception {
        HttpResponse<String> response = postJson("/api/prompts", buildPromptRequest(project, group, name, userMessage, null));
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON_MAPPER.readTree(response.body());
    }

    private JsonNode updatePrompt(JsonNode project, String promptId, String group, String name, String userMessage) throws Exception {
        HttpResponse<String> response = putJson(
                "/api/prompts/" + promptId,
                buildPromptRequest(project, group, name, userMessage, promptId)
        );
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON_MAPPER.readTree(response.body());
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = sendRequest("GET", path, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON_MAPPER.readTree(response.body());
    }

    private HttpResponse<String> postJson(String path, JsonNode body) throws Exception {
        return sendRequest("POST", path, body);
    }

    private HttpResponse<String> putJson(String path, JsonNode body) throws Exception {
        return sendRequest("PUT", path, body);
    }

    private HttpResponse<String> sendRequest(String method, String path, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60));

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

    private ObjectNode buildPromptRequest(JsonNode project,
                                          String group,
                                          String name,
                                          String userMessage,
                                          String promptId) {
        ObjectNode request = JSON_MAPPER.createObjectNode();
        if (promptId != null) {
            request.put("id", promptId);
        }
        request.put("group", group);
        request.put("name", name);
        request.put("description", "backend facade contract test");
        request.put("version", "1.0.0-SNAPSHOT");
        request.put("repositoryUrl", project.path("repositoryUrl").asText());
        request.put("placeholderStartPattern", "{{");
        request.put("placeholderEndPattern", "}}");

        ObjectNode placeholders = request.putObject("placeholder");
        placeholders.put("customer_name", "Taylor");

        ObjectNode extensionRoot = request.putObject("extensions").putObject("x-e2e");
        extensionRoot.put("suite", "backend-facade-infra");
        extensionRoot.put("runId", UUID.randomUUID().toString());

        ObjectNode requestBody = request.putObject("request");
        requestBody.put("type", "chat/completion");
        requestBody.put("vendor", "openai");
        requestBody.put("model", "gpt-4o-mini");
        requestBody.put("url", "https://api.openai.com/v1/chat/completions");

        ObjectNode params = requestBody.putObject("parameters");
        params.put("temperature", 0.35d);
        params.put("topP", 0.95d);
        params.put("maxTokens", 256);
        params.put("frequencyPenalty", 0.1d);
        params.put("presencePenalty", 0.1d);
        params.put("stream", false);

        ArrayNode messages = requestBody.putArray("messages");
        messages.addObject()
                .put("role", "SYSTEM")
                .put("content", "You are an assistant for support triage.");
        messages.addObject()
                .put("role", "USER")
                .put("content", userMessage);

        return request;
    }

    private Optional<String> awaitRemoteFile(String repoUrl, String relativePath) {
        // Bumped from 60s to 180s for the #352 deferred-push flow: the remote write now waits for the
        // PromptExecutedEvent (real LLM round-trip via OpenAI) before the amend+push fires.
        return await()
                .atMost(Duration.ofSeconds(180))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> GiteaRepositoryHelper.fetchRawFile(
                                HTTP_CLIENT,
                                repoUrl,
                                "development",
                                relativePath,
                                gitea.getAdminToken()
                        ),
                        Optional::isPresent);
    }

    private static JsonNode findProjectById(JsonNode projectsArray, String projectId) {
        if (projectsArray == null || !projectsArray.isArray()) {
            return null;
        }
        for (JsonNode entry : projectsArray) {
            if (projectId.equals(entry.path("id").asText())) {
                return entry;
            }
        }
        return null;
    }

    private String uniqueRepoName(String prefix) {
        return "backend-facade-%s-%s".formatted(prefix, System.nanoTime());
    }

    private String uniquePromptName(String prefix) {
        return "%s-%s".formatted(prefix, System.nanoTime());
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
