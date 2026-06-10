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

package dev.promptlm.web;

import dev.promptlm.lifecycle.application.PromptSpecAlreadyExistsException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.promptlm.domain.AppContext;
import dev.promptlm.domain.projectspec.ProjectSpec;
import dev.promptlm.domain.promptspec.ChatCompletionRequest;
import dev.promptlm.domain.promptspec.ChatCompletionResponse;
import dev.promptlm.domain.promptspec.Execution;
import dev.promptlm.domain.promptspec.ExecutionKind;
import dev.promptlm.domain.promptspec.PromptEvaluationDefinition;
import dev.promptlm.domain.promptspec.PromptSpec;
import dev.promptlm.domain.promptspec.ReleaseMetadata;
import dev.promptlm.lifecycle.PromptLifecycleFacade;
import dev.promptlm.execution.PromptExecutor;
import dev.promptlm.release.OnInfraFailure;
import dev.promptlm.release.PreReleaseInfrastructureFailure;
import dev.promptlm.release.PreReleasePromptFailure;
import dev.promptlm.store.api.PromptStore;
import dev.promptlm.store.api.Revision;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PromptSpecController.class)
@RecordApplicationEvents
class PromptSpecControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PromptStore promptStore;

    @MockitoBean
    private PromptExecutor promptExecutor;

    @MockitoBean
    private PromptLifecycleFacade promptLifecycleFacade;

    @MockitoBean
    private AppContext appContext;

    @MockitoBean
    private PromptSpecLifecycleDeriver lifecycleDeriver;

    @MockitoBean
    private dev.promptlm.pricing.ModelPricingService modelPricingService;

    @MockitoBean
    private SseEmitterRegistry sseEmitterRegistry;

    @MockitoBean
    private SseStatusPublisher sseStatusPublisher;

    @Autowired(required = false)
    private ApplicationEvents applicationEvents;

    @Test
    void getDefaultTemplateReturnsCanonicalDraftSeed() throws Exception {
        PromptSpec.Placeholders placeholders = new PromptSpec.Placeholders();
        placeholders.setStartPattern("{{");
        placeholders.setEndPattern("}}");
        placeholders.setList(List.of(new PromptSpec.Placeholder("customer_name", "Taylor")));

        PromptSpec template = PromptSpec.builder()
                .withGroup("support")
                .withName("support-prompt")
                .withVersion("1.0.0-SNAPSHOT")
                .withRevision(1)
                .withDescription("Assist support agents")
                .withRequest(ChatCompletionRequest.builder()
                        .withVendor("openai")
                        .withModel("gpt-4o")
                        .withMessages(List.of(
                                ChatCompletionRequest.Message.builder()
                                        .withRole("system")
                                        .withContent("You are a helpful assistant.")
                                        .build(),
                                ChatCompletionRequest.Message.builder()
                                        .withRole("user")
                                        .withContent("Help the customer.")
                                        .build()))
                        .build())
                .withPlaceholders(placeholders)
                .build();
        when(promptLifecycleFacade.createDefaultPromptSpec()).thenReturn(template);

        mockMvc.perform(get("/api/prompts/template"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value("support-prompt"))
                .andExpect(jsonPath("$.group").value("support"))
                .andExpect(jsonPath("$.description").value("Assist support agents"))
                .andExpect(jsonPath("$.request.vendor").value("openai"))
                .andExpect(jsonPath("$.request.model").value("gpt-4o"))
                .andExpect(jsonPath("$.request.messages[0].role").value("system"))
                .andExpect(jsonPath("$.request.messages[0].content").value("You are a helpful assistant."))
                .andExpect(jsonPath("$.request.messages[1].role").value("user"))
                .andExpect(jsonPath("$.request.messages[1].content").value("Help the customer."))
                .andExpect(jsonPath("$.placeholders.startPattern").value("{{"))
                .andExpect(jsonPath("$.placeholders.endPattern").value("}}"))
                .andExpect(jsonPath("$.placeholders.list[0].name").value("customer_name"))
                .andExpect(jsonPath("$.placeholders.list[0].value").value("Taylor"));
    }

    @Test
    void listPromptsShouldNotFailWhenGroupIsNullAndSortingByGroup() throws Exception {
        PromptSpec promptWithNullGroup = PromptSpec.builder()
                .withName("prompt-a")
                .withVersion("0.1.0")
                .withRevision(1)
                .withDescription("desc")
                .withRequest(null)
                .build();

        when(promptStore.listAllPrompts()).thenReturn(List.of(promptWithNullGroup));

        mockMvc.perform(get("/api/prompts").queryParam("sortBy", "group"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    void releaseEndpointDelegatesToLifecycleAndSetsReleaseStateHeader() throws Exception {
        String promptId = "support/welcome";
        PromptSpec stored = PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion("1.0.0-SNAPSHOT")
                .withRevision(3)
                .withDescription("desc")
                .withRequest(ChatCompletionRequest.builder()
                        .withVendor("openai")
                        .withModel("gpt-4o")
                        .withMessages(List.of())
                        .build())
                .build()
                .withId(promptId);
        PromptSpec requested = stored.withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_REQUESTED,
                ReleaseMetadata.MODE_PR_TWO_PHASE,
                "1.0.0",
                "support/welcome-v1.0.0",
                "release/support-welcome-1.0.0",
                11,
                "https://github.com/promptLM/promptlm-app/pull/11",
                false
        ));

        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));
        when(promptLifecycleFacade.release(promptId, dev.promptlm.release.OnInfraFailure.REJECT)).thenReturn(requested);

        mockMvc.perform(post("/api/prompts/{promptSpecId}/release", promptId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-PromptLM-Release-State", "requested"))
                .andExpect(jsonPath("$.extensions['x-promptlm'].release.state").value("requested"));

        verify(promptLifecycleFacade).release(promptId, dev.promptlm.release.OnInfraFailure.REJECT);
        verify(promptStore, times(0)).release(any(PromptSpec.class));
    }

    @Test
    void releaseEndpointPropagatesPromptFailureAs422() throws Exception {
        String promptId = "support/welcome";
        PromptSpec stored = baseSpec(promptId);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));
        when(promptLifecycleFacade.release(promptId, OnInfraFailure.REJECT))
                .thenThrow(new PreReleasePromptFailure(promptId, null, new IllegalArgumentException("placeholder X missing")));

        mockMvc.perform(post("/api/prompts/{promptSpecId}/release", promptId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRE_RELEASE_PROMPT_FAILURE"));
    }

    @Test
    void releaseEndpointPropagatesInfraFailureAs503ByDefault() throws Exception {
        String promptId = "support/welcome";
        PromptSpec stored = baseSpec(promptId);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));
        when(promptLifecycleFacade.release(promptId, OnInfraFailure.REJECT))
                .thenThrow(new PreReleaseInfrastructureFailure(promptId, null, new java.io.IOException("vendor 503")));

        mockMvc.perform(post("/api/prompts/{promptSpecId}/release", promptId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRE_RELEASE_INFRA_FAILURE"));
    }

    @Test
    void releaseWithOnInfraFailureRecordSucceedsAndForwardsRecordToFacade() throws Exception {
        String promptId = "support/welcome";
        PromptSpec stored = baseSpec(promptId);
        PromptSpec released = stored.withVersion("1.0.0").withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_RELEASED,
                ReleaseMetadata.MODE_DIRECT,
                "1.0.0",
                "support/welcome-v1.0.0",
                "main",
                null,
                null,
                false));
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));
        when(promptLifecycleFacade.release(promptId, OnInfraFailure.RECORD)).thenReturn(released);

        mockMvc.perform(post("/api/prompts/{promptSpecId}/release", promptId).param("onInfraFailure", "record"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-PromptLM-Release-State", "released"));

        verify(promptLifecycleFacade).release(promptId, OnInfraFailure.RECORD);
    }

    @Test
    void releaseRejectsUnsupportedOnInfraFailureValue() throws Exception {
        String promptId = "support/welcome";
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(baseSpec(promptId)));

        mockMvc.perform(post("/api/prompts/{promptSpecId}/release", promptId).param("onInfraFailure", "ignore"))
                .andExpect(status().isBadRequest());
    }

    private static PromptSpec baseSpec(String promptId) {
        String[] parts = promptId.split("/", 2);
        return PromptSpec.builder()
                .withGroup(parts[0])
                .withName(parts[1])
                .withVersion("1.0.0-SNAPSHOT")
                .withRevision(3)
                .withDescription("desc")
                .withRequest(ChatCompletionRequest.builder()
                        .withVendor("openai")
                        .withModel("gpt-4o")
                        .withMessages(List.of())
                        .build())
                .build()
                .withId(promptId);
    }

    @Test
    void getByIdIncludesDerivedLifecycleStateInResponse() throws Exception {
        String promptId = "welcome";
        PromptSpec stored = baseSpec("support/" + promptId);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));
        when(lifecycleDeriver.deriveResult(any(PromptSpec.class)))
                .thenReturn(new PromptSpecLifecycleDeriver.Result(
                        dev.promptlm.domain.promptspec.PromptSpecLifecycleState.PUSHED, null));

        mockMvc.perform(get("/api/prompts/{promptSpecId}", promptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("pushed"));
    }

    @Test
    void getByIdOmitsLifecycleStateWhenDeriverReturnsEmpty() throws Exception {
        String promptId = "welcome";
        PromptSpec stored = baseSpec("support/" + promptId);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));
        when(lifecycleDeriver.deriveResult(any(PromptSpec.class)))
                .thenReturn(PromptSpecLifecycleDeriver.Result.EMPTY);

        mockMvc.perform(get("/api/prompts/{promptSpecId}", promptId))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("\"lifecycleState\""))))
                .andExpect(content().string(not(containsString("\"headShortSha\""))));
    }

    @Test
    void getByIdNeverEmitsDraftStateBecauseDraftIsClientOnly() throws Exception {
        // Belt-and-braces: even if a misbehaving deriver returned DRAFT, the
        // controller path would still echo it (the deriver is the sole writer).
        // This test pins the contract that the API path passes the value
        // through to JSON exactly, so callers can rely on the enum vocabulary —
        // and documents that the production deriver never returns DRAFT.
        String promptId = "welcome";
        PromptSpec stored = baseSpec("support/" + promptId);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));
        when(lifecycleDeriver.deriveResult(any(PromptSpec.class)))
                .thenReturn(new PromptSpecLifecycleDeriver.Result(
                        dev.promptlm.domain.promptspec.PromptSpecLifecycleState.SAVED, null));

        mockMvc.perform(get("/api/prompts/{promptSpecId}", promptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("saved"));
    }

    @Test
    void getByIdIncludesHeadShortShaWhenDeriverProvidesIt() throws Exception {
        String promptId = "welcome";
        PromptSpec stored = baseSpec("support/" + promptId);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));
        when(lifecycleDeriver.deriveResult(any(PromptSpec.class)))
                .thenReturn(new PromptSpecLifecycleDeriver.Result(
                        dev.promptlm.domain.promptspec.PromptSpecLifecycleState.PUSHED, "a1b2c3d"));

        mockMvc.perform(get("/api/prompts/{promptSpecId}", promptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("pushed"))
                .andExpect(jsonPath("$.headShortSha").value("a1b2c3d"));
    }

    @Test
    void getByIdExposesReleaseTagWhenExtensionPresent() throws Exception {
        String promptId = "welcome";
        ReleaseMetadata release = new ReleaseMetadata(
                ReleaseMetadata.STATE_RELEASED,
                ReleaseMetadata.MODE_DIRECT,
                "1.4.0",
                "v1.4",
                "main",
                null,
                null,
                null);
        PromptSpec stored = baseSpec("support/" + promptId)
                .withReleaseMetadata(release);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));
        when(lifecycleDeriver.deriveResult(any(PromptSpec.class)))
                .thenReturn(PromptSpecLifecycleDeriver.Result.EMPTY);

        mockMvc.perform(get("/api/prompts/{promptSpecId}", promptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseTag").value("v1.4"));
    }

    @Test
    void viewOnRemoteUrlFieldIsNotPresentOnPromptSpecBoundary() throws Exception {
        // The View-on-GitHub link is composed by the frontend from the
        // project's remote URL + the spec's path + headShortSha (see #188's
        // refactor). The server therefore no longer attaches a viewOnRemoteUrl
        // field to PromptSpec. Hardening: a client write carrying that field
        // is silently dropped by Jackson, and read responses never emit it.
        String promptId = "welcome";
        PromptSpec stored = baseSpec("support/" + promptId);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));

        mockMvc.perform(get("/api/prompts/{promptSpecId}", promptId))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("\"viewOnRemoteUrl\""))));

        String payload = """
                {
                  "name": "welcome",
                  "group": "support",
                  "viewOnRemoteUrl": "https://evil.example/inject"
                }
                """;
        // Sanity: deserialisation succeeds and the unknown property is
        // ignored. The field is gone from the type, so there's no getter to
        // assert against — instead re-serialising and confirming the URL is
        // not echoed back gives equivalent coverage.
        PromptSpec readBack = objectMapper.readValue(payload, PromptSpec.class);
        String roundTripped = objectMapper.writeValueAsString(readBack);
        assertThat(roundTripped).doesNotContain("viewOnRemoteUrl");
        assertThat(roundTripped).doesNotContain("evil.example");
    }

    @Test
    void completeReleaseEndpointDelegatesToLifecycleAndSetsReleaseStateHeader() throws Exception {
        String promptId = "support/welcome";
        PromptSpec stored = PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion("1.0.0-SNAPSHOT")
                .withRevision(3)
                .withDescription("desc")
                .withRequest(ChatCompletionRequest.builder()
                        .withVendor("openai")
                        .withModel("gpt-4o")
                        .withMessages(List.of())
                        .build())
                .build()
                .withId(promptId);
        PromptSpec released = stored.withVersion("1.0.0").withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_RELEASED,
                ReleaseMetadata.MODE_PR_TWO_PHASE,
                "1.0.0",
                "support/welcome-v1.0.0",
                "main",
                11,
                "https://github.com/promptLM/promptlm-app/pull/11",
                false
        ));

        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));
        when(promptLifecycleFacade.completeRelease(promptId, "11")).thenReturn(released);

        mockMvc.perform(post("/api/prompts/{promptSpecId}/release/complete", promptId).queryParam("pr", "11"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-PromptLM-Release-State", "released"))
                .andExpect(jsonPath("$.extensions['x-promptlm'].release.state").value("released"));

        verify(promptLifecycleFacade).completeRelease(promptId, "11");
    }

    @Test
    void getPromptStatsDerivesProjectCountAndLastUpdatedFromGitMetadata(@TempDir Path tempDir) throws Exception {
        Instant commitInstant = Instant.parse("2026-01-02T03:04:05Z");
        Path repository = initializeRepositoryWithPromptCommit(tempDir, commitInstant);

        ProjectSpec activeProject = new ProjectSpec();
        activeProject.setId(UUID.randomUUID());
        activeProject.setRepoDir(repository);

        PromptSpec activePrompt = PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("active prompt")
                .withRequest(null)
                .build();

        PromptSpec retiredPrompt = PromptSpec.builder()
                .withGroup("legacy")
                .withName("old-welcome")
                .withVersion("0.9.0")
                .withRevision(1)
                .withDescription("retired prompt")
                .withRequest(null)
                .withStatus(PromptSpec.PromptStatus.RETIRED)
                .build();

        when(promptStore.listAllPrompts(true)).thenReturn(List.of(activePrompt, retiredPrompt));
        when(appContext.getActiveProject()).thenReturn(activeProject);
        when(appContext.getProjects()).thenReturn(List.of(activeProject));

        mockMvc.perform(get("/api/prompts/stats"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalPrompts").value(2))
                .andExpect(jsonPath("$.activePrompts").value(1))
                .andExpect(jsonPath("$.retiredPrompts").value(1))
                .andExpect(jsonPath("$.activeProjects").value(1))
                .andExpect(jsonPath("$.countByGroup.support").value(1))
                .andExpect(jsonPath("$.countByGroup.legacy").value(1))
                .andExpect(jsonPath("$.lastUpdated").value("2026-01-02T03:04:05Z"));
    }

    @Test
    void getPromptGroupsHonorsIncludeRetiredFlag() throws Exception {
        PromptSpec activePrompt = PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("active prompt")
                .withRequest(null)
                .build();

        PromptSpec retiredPrompt = PromptSpec.builder()
                .withGroup("legacy")
                .withName("old-welcome")
                .withVersion("0.9.0")
                .withRevision(1)
                .withDescription("retired prompt")
                .withRequest(null)
                .withStatus(PromptSpec.PromptStatus.RETIRED)
                .build();

        when(promptStore.listAllPrompts(false)).thenReturn(List.of(activePrompt));
        when(promptStore.listAllPrompts(true)).thenReturn(List.of(activePrompt, retiredPrompt));

        mockMvc.perform(get("/api/prompts/groups"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0]").value("support"))
                .andExpect(jsonPath("$[1]").doesNotExist());

        mockMvc.perform(get("/api/prompts/groups").queryParam("includeRetired", "true"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0]").value("legacy"))
                .andExpect(jsonPath("$[1]").value("support"));
    }

    /**
     * Verifies that /api/prompts/stats succeeds when no active project is configured,
     * returning prompt counts with null lastUpdated. Regression test for the
     * IllegalStateException thrown on fresh app start before any repository is set up.
     */
    @Test
    void getPromptStatsReturnsStatsWithNullLastUpdatedWhenNoActiveProjectIsSet() throws Exception {
        PromptSpec activePrompt = PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("active prompt")
                .withRequest(null)
                .build();

        when(promptStore.listAllPrompts(true)).thenReturn(List.of(activePrompt));
        when(appContext.getActiveProject()).thenReturn(null);
        when(appContext.getProjects()).thenReturn(List.of());

        mockMvc.perform(get("/api/prompts/stats"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalPrompts").value(1))
                .andExpect(jsonPath("$.activePrompts").value(1))
                .andExpect(jsonPath("$.retiredPrompts").value(0))
                .andExpect(jsonPath("$.activeProjects").value(0))
                .andExpect(jsonPath("$.lastUpdated").doesNotExist());
    }

    @Test
    void getPromptStatsFailsWhenActiveProjectRepoPathDoesNotExist() throws Exception {
        PromptSpec activePrompt = PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("active prompt")
                .withRequest(null)
                .build();

        ProjectSpec activeProject = new ProjectSpec();
        activeProject.setId(UUID.randomUUID());
        activeProject.setRepoDir(Path.of("/tmp/does-not-exist-for-stats"));

        when(promptStore.listAllPrompts(true)).thenReturn(List.of(activePrompt));
        when(appContext.getActiveProject()).thenReturn(activeProject);
        when(appContext.getProjects()).thenReturn(List.of(activeProject));

        assertThatThrownBy(() -> mockMvc.perform(get("/api/prompts/stats")))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("Failed to resolve active-project git metadata");
    }

    @Test
    void executeStoredPromptExecutesRequestBodyDraftWhenProvidedButDoesNotRecordHistory() throws Exception {
        // Issue #183 / D-183-5: when the request body carries a PromptSpec
        // (e.g. the editor's current form state) AND the editor flags the
        // payload as a draft (the user has unsaved edits), the controller
        // must execute *that* spec rather than the stored YAML — and return
        // the executed result to the UI so it can render the response — but
        // the execution is NOT recorded in history. Draft executions are
        // ephemeral: history only ever contains runs of actually-stored
        // content.
        //
        // Note: the draft-ness is signalled explicitly via the request's
        // `draft` flag (see ExecutePromptRequest). The earlier implementation
        // inferred it from body-vs-stored semantic-hash divergence; that
        // proved fragile in practice and is the bug this test originally
        // covered with the wrong half of the contract — see
        // executeStoredPromptRecordsHistoryWhenDraftFlagIsFalseEvenIfBodyDiverges
        // for the regression pin (issue #140).
        String promptId = "prompt-a";

        ChatCompletionRequest storedRequestPayload = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("Stored ping")
                                .build()
                ))
                .withParameters(Map.of())
                .build();

        ChatCompletionRequest draftRequestPayload = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("Draft ping — unsaved edit")
                                .build()
                ))
                .withParameters(Map.of())
                .build();

        PromptSpec storedPrompt = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-a")
                .withVersion("1.0.0")
                .withRevision(3)
                .withDescription("stored desc")
                .withRequest(storedRequestPayload)
                .build()
                .withId(promptId);

        // Draft uses the same id and group/name (the form does not let users
        // mutate those once edit mode is open), but its request content is
        // different from what is on disk.
        PromptSpec draftPrompt = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-a")
                .withVersion("1.0.0")
                .withRevision(3)
                .withDescription("draft desc — unsaved")
                .withRequest(draftRequestPayload)
                .build()
                .withId(promptId);

        ChatCompletionResponse response = new ChatCompletionResponse(10L, null, "Pong");
        // The executor returns whatever spec it was handed, with a response
        // attached — the controller passes the draft, so we mirror that.
        PromptSpec executedDraft = draftPrompt.withResponse(response);

        when(promptExecutor.runPromptAndAttachResponse(any(PromptSpec.class))).thenReturn(executedDraft);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(storedPrompt));

        // draft=true: the editor is flagging this as an unsaved-edit run, so
        // it must execute the body but skip recording history.
        ExecutePromptRequest executePromptRequest = new ExecutePromptRequest(draftPrompt, true);

        mockMvc.perform(post("/api/prompts/{promptSpecId}/execute", promptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(executePromptRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                // (a) The response body reflects the *draft*-modified content,
                // confirming the draft (not the stored spec) was executed.
                .andExpect(jsonPath("$.description").value("draft desc — unsaved"))
                .andExpect(jsonPath("$.request.messages[0].content")
                        .value("Draft ping — unsaved edit"))
                .andExpect(jsonPath("$.response.content").value("Pong"));

        // The executor was called with the *draft* spec.
        ArgumentCaptor<PromptSpec> executedPromptCaptor = ArgumentCaptor.forClass(PromptSpec.class);
        verify(promptExecutor).runPromptAndAttachResponse(executedPromptCaptor.capture());
        PromptSpec executed = executedPromptCaptor.getValue();
        assertThat(executed.getDescription()).isEqualTo("draft desc — unsaved");
        ChatCompletionRequest executedRequest = (ChatCompletionRequest) executed.getRequest();
        assertThat(executedRequest.getMessages())
                .extracting(ChatCompletionRequest.Message::getContent)
                .containsExactly("Draft ping — unsaved edit");
        // Path id is authoritative.
        assertThat(executed.getId()).isEqualTo(promptId);

        // (b) recordExecution must NOT be called for draft runs — history is
        // left untouched. D-183-5 reverses the earlier behaviour pinned by
        // D-183-3, because recording under the stored revision while the
        // executed content is the draft produces misleading history rows.
        verify(promptLifecycleFacade, never())
                .recordExecution(eq(promptId), any(Execution.class));
    }

    @Test
    void executeStoredPromptForcesPathIdOntoBodySpecWhenBodyIdIsBlank() throws Exception {
        // Issue #183: a draft body might omit the spec id (the editor doesn't
        // always populate it). The controller must still execute it, with the
        // path id forced onto the spec.
        String promptId = "prompt-b";

        ChatCompletionRequest payload = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("Draft body, no id")
                                .build()
                ))
                .withParameters(Map.of())
                .build();

        PromptSpec storedPrompt = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-b")
                .withVersion("1.0.0")
                .withRevision(2)
                .withDescription("stored")
                .withRequest(payload)
                .build()
                .withId(promptId);

        // Note: no .withId(...) on the draft — id is null in the request body.
        PromptSpec draftPrompt = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-b")
                .withVersion("1.0.0")
                .withRevision(2)
                .withDescription("draft, no id")
                .withRequest(payload)
                .build();

        ChatCompletionResponse response = new ChatCompletionResponse(5L, null, "Pong");
        PromptSpec executedDraft = draftPrompt.withId(promptId).withResponse(response);
        PromptSpec persisted = storedPrompt.withResponse(response);

        when(promptExecutor.runPromptAndAttachResponse(any(PromptSpec.class))).thenReturn(executedDraft);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(storedPrompt));
        when(promptLifecycleFacade.recordExecution(eq(promptId), any(Execution.class)))
                .thenReturn(persisted);

        mockMvc.perform(post("/api/prompts/{promptSpecId}/execute", promptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ExecutePromptRequest(draftPrompt))))
                .andExpect(status().isOk());

        ArgumentCaptor<PromptSpec> executedCaptor = ArgumentCaptor.forClass(PromptSpec.class);
        verify(promptExecutor).runPromptAndAttachResponse(executedCaptor.capture());
        assertThat(executedCaptor.getValue().getId()).isEqualTo(promptId);
        assertThat(executedCaptor.getValue().getDescription()).isEqualTo("draft, no id");
    }

    @Test
    void executeStoredPromptFallsBackToStoredSpecWhenNoRequestBody() throws Exception {
        // No request body at all — the controller executes the stored spec.
        // This preserves the legacy `executeStoredPrompt(id)` call site in
        // PromptDetail.tsx and any future no-body callers.
        String promptId = "prompt-c";

        ChatCompletionRequest payload = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("Stored only")
                                .build()
                ))
                .withParameters(Map.of())
                .build();

        PromptSpec storedPrompt = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-c")
                .withVersion("1.0.0")
                .withRevision(7)
                .withDescription("stored")
                .withRequest(payload)
                .build()
                .withId(promptId);

        ChatCompletionResponse response = new ChatCompletionResponse(5L, null, "Pong");
        PromptSpec executed = storedPrompt.withResponse(response);
        PromptSpec persisted = storedPrompt.withResponse(response);

        when(promptExecutor.runPromptAndAttachResponse(any(PromptSpec.class))).thenReturn(executed);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(storedPrompt));
        when(promptLifecycleFacade.recordExecution(eq(promptId), any(Execution.class)))
                .thenReturn(persisted);

        // No body — sending an empty content (Spring treats `required=false`).
        mockMvc.perform(post("/api/prompts/{promptSpecId}/execute", promptId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        ArgumentCaptor<PromptSpec> executedCaptor = ArgumentCaptor.forClass(PromptSpec.class);
        verify(promptExecutor).runPromptAndAttachResponse(executedCaptor.capture());
        assertThat(executedCaptor.getValue().getDescription()).isEqualTo("stored");
        assertThat(executedCaptor.getValue().getId()).isEqualTo(promptId);
    }

    @Test
    void executeStoredPromptRecordsHistoryWhenDraftFlagIsFalseEvenIfBodyDiverges() throws Exception {
        // Issue #140 / #183 reconciliation — REGRESSION PIN for the bug that
        // blocked HappyPath#runPromptPersistsManualExecution on every PR for
        // ~two weeks.
        //
        // The editor's Run action always sends the current form state as the
        // request body (PromptFormShell#handleEditorRun). That body is *never*
        // byte-identical to the stored YAML: TS-side normalisation upper-cases
        // roles, drops/synthesises optional fields, reshapes placeholders, etc.
        //
        // The earlier implementation inferred draft-vs-clean by comparing
        // `PromptSpec#computeSemanticHash` of body vs stored. Any one of the
        // normalisation differences was enough to flip that hash, so every
        // clean Run was misclassified as a draft and the MANUAL Execution was
        // silently dropped. (#246 + #277 chased two of those normalisation
        // gaps but never closed the design.)
        //
        // New contract: the editor sends `draft: false` (the default) for a
        // clean Run and `draft: true` only when it knows the user has unsaved
        // edits (PromptFormShell tracks `isDirty`). The controller trusts the
        // flag and ignores body-vs-stored shape divergence.
        //
        // This test pins the new contract: body diverges semantically from
        // stored, draft flag is false → the MANUAL Execution MUST be recorded.
        String promptId = "prompt-clean-run";

        ChatCompletionRequest storedRequest = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of(
                        // Stored YAML happens to have lower-case role.
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("Identical content")
                                .build()
                ))
                .withParameters(Map.of())
                .build();

        // Body from the editor: same content, different role casing (UPPER).
        // Under the old hash heuristic this would be misclassified as a draft
        // and the execution silently dropped — exactly the production bug.
        ChatCompletionRequest editorRequest = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("USER")
                                .withContent("Identical content")
                                .build()
                ))
                .withParameters(Map.of())
                .build();

        PromptSpec storedPrompt = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-clean-run")
                .withVersion("1.0.0")
                .withRevision(4)
                .withDescription("stored")
                .withRequest(storedRequest)
                .build()
                .withId(promptId);

        PromptSpec cleanDraft = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-clean-run")
                .withVersion("1.0.0")
                .withRevision(4)
                .withDescription("stored")
                .withRequest(editorRequest)
                .build()
                .withId(promptId);

        // Sanity check the regression scenario: the hashes really do diverge,
        // so this test would have FAILED under the old controller logic.
        assertThat(cleanDraft.hasSemanticChangesComparedTo(storedPrompt))
                .as("editor body diverges from stored YAML by case alone — the old "
                        + "hash heuristic would have misclassified this as a draft")
                .isTrue();

        ChatCompletionResponse response = new ChatCompletionResponse(7L, null, "Pong");
        PromptSpec executed = cleanDraft.withResponse(response);
        PromptSpec persisted = storedPrompt.withResponse(response);

        when(promptExecutor.runPromptAndAttachResponse(any(PromptSpec.class))).thenReturn(executed);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(storedPrompt));
        when(promptLifecycleFacade.recordExecution(eq(promptId), any(Execution.class)))
                .thenReturn(persisted);

        // draft flag explicitly false (also the default).
        ExecutePromptRequest executeRequest = new ExecutePromptRequest(cleanDraft, false);

        mockMvc.perform(post("/api/prompts/{promptSpecId}/execute", promptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(executeRequest)))
                .andExpect(status().isOk());

        // The execution MUST be recorded — this is what
        // HappyPathUserJourneyTest#runPromptPersistsManualExecution observes.
        ArgumentCaptor<Execution> executionCaptor = ArgumentCaptor.forClass(Execution.class);
        verify(promptLifecycleFacade).recordExecution(eq(promptId), executionCaptor.capture());
        assertThat(executionCaptor.getValue().kindOrManual())
                .as("clean editor Run records a MANUAL execution")
                .isEqualTo(ExecutionKind.MANUAL);
    }

    @Test
    void executeStoredPromptOmittedDraftFieldDefaultsToRecordingHistory() throws Exception {
        // Backwards-compat / safety net: a request whose JSON body omits the
        // `draft` field entirely (older client, hand-crafted call) must
        // default to draft=false → recording history. This pins the default
        // so a future Jackson-config change can't silently flip semantics.
        String promptId = "prompt-default-draft";

        ChatCompletionRequest payload = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("hello")
                                .build()
                ))
                .withParameters(Map.of())
                .build();

        PromptSpec storedPrompt = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-default-draft")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("stored")
                .withRequest(payload)
                .build()
                .withId(promptId);

        ChatCompletionResponse response = new ChatCompletionResponse(3L, null, "Pong");
        PromptSpec executed = storedPrompt.withResponse(response);
        PromptSpec persisted = storedPrompt.withResponse(response);

        when(promptExecutor.runPromptAndAttachResponse(any(PromptSpec.class))).thenReturn(executed);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(storedPrompt));
        when(promptLifecycleFacade.recordExecution(eq(promptId), any(Execution.class)))
                .thenReturn(persisted);

        // Hand-crafted body that omits the `draft` field. Note we still send
        // a `promptSpec` so the body branch in the controller is taken.
        String bodyJson = "{\"promptSpec\":"
                + objectMapper.writeValueAsString(storedPrompt)
                + "}";

        mockMvc.perform(post("/api/prompts/{promptSpecId}/execute", promptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isOk());

        verify(promptLifecycleFacade)
                .recordExecution(eq(promptId), any(Execution.class));
    }

    @Test
    void executeStoredPromptRejectsBodyWithMismatchedId() throws Exception {
        // Path id is authoritative. A draft body carrying a different id is a
        // 400. Pins the existing safeguard against id-smuggling.
        String pathId = "prompt-d";
        String otherId = "prompt-e";

        ChatCompletionRequest payload = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("Mismatched id")
                                .build()
                ))
                .withParameters(Map.of())
                .build();

        PromptSpec stored = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-d")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("stored")
                .withRequest(payload)
                .build()
                .withId(pathId);

        PromptSpec mismatchedDraft = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-e")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("draft with wrong id")
                .withRequest(payload)
                .build()
                .withId(otherId);

        when(promptStore.getLatestVersion(pathId)).thenReturn(Optional.of(stored));

        mockMvc.perform(post("/api/prompts/{promptSpecId}/execute", pathId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ExecutePromptRequest(mismatchedDraft))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void executeStoredPromptReturnsExecutionsSortedNewestFirst() throws Exception {
        String promptId = "prompt-sort";

        ChatCompletionRequest requestPayload = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("Ping")
                                .build()
                ))
                .withParameters(Map.of())
                .build();

        PromptSpec storedPrompt = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-sort")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("desc")
                .withRequest(requestPayload)
                .build()
                .withId(promptId);

        ChatCompletionResponse responseLatest = new ChatCompletionResponse(10L, null, "latest");
        // Store-order: oldest first. API view must flip to newest first.
        Execution older = new Execution(
                "exec-older",
                Instant.parse("2026-05-12T10:00:00Z"),
                new ChatCompletionResponse(10L, null, "older"),
                null, null);
        Execution newer = new Execution(
                "exec-newer",
                Instant.parse("2026-05-12T12:00:00Z"),
                responseLatest,
                null, null);

        PromptSpec persisted = storedPrompt
                .withResponse(responseLatest)
                .withExecutions(List.of(older, newer));

        when(promptExecutor.runPromptAndAttachResponse(any(PromptSpec.class)))
                .thenReturn(storedPrompt.withResponse(responseLatest));
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(storedPrompt));
        when(promptLifecycleFacade.recordExecution(eq(promptId), any(Execution.class))).thenReturn(persisted);

        ExecutePromptRequest executePromptRequest = new ExecutePromptRequest(null);

        mockMvc.perform(post("/api/prompts/{promptSpecId}/execute", promptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(executePromptRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executions[0].id").value("exec-newer"))
                .andExpect(jsonPath("$.executions[1].id").value("exec-older"));
    }

    @Test
    void getByIdReturnsExecutionsSortedNewestFirst() throws Exception {
        String promptId = "prompt-get";

        ChatCompletionRequest requestPayload = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("Ping")
                                .build()
                ))
                .withParameters(Map.of())
                .build();

        Execution older = new Execution(
                "exec-older",
                Instant.parse("2026-05-12T10:00:00Z"),
                new ChatCompletionResponse(10L, null, "older"),
                null, null);
        Execution newer = new Execution(
                "exec-newer",
                Instant.parse("2026-05-12T12:00:00Z"),
                new ChatCompletionResponse(10L, null, "newer"),
                null, null);

        PromptSpec stored = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-get")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("desc")
                .withRequest(requestPayload)
                .build()
                .withId(promptId)
                .withExecutions(List.of(older, newer));

        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));

        mockMvc.perform(get("/api/prompts/{promptSpecId}", promptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executions[0].id").value("exec-newer"))
                .andExpect(jsonPath("$.executions[1].id").value("exec-older"));
    }

    @Test
    void getByIdProjectsCostUsdOnReadWhenModelHasPricing() throws Exception {
        // Refactor of issue #182: USD cost is derived at the view layer rather
        // than persisted on Execution, so changes to the operator-managed
        // pricing table never invalidate historical records. The controller
        // delegates the computation to ModelPricingService and attaches the
        // result as `costUsd` on the API JSON projection. A known model with
        // token counts produces a value; the value comes straight from the
        // (mocked) pricing service.
        String promptId = "prompt-priced";
        ChatCompletionRequest requestPayload = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("Ping")
                                .build()))
                .withParameters(Map.of())
                .build();

        Execution priced = new Execution(
                "exec-priced",
                Instant.parse("2026-05-12T12:00:00Z"),
                new ChatCompletionResponse(10L, null, "ok"),
                null,
                null,
                500L,
                100,
                200,
                null,
                null,
                "1",
                null,
                true,
                null);

        PromptSpec stored = PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("desc")
                .withRequest(requestPayload)
                .build()
                .withId(promptId)
                .withExecutions(List.of(priced));

        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));
        when(modelPricingService.computeCost("gpt-4o", 100, 200))
                .thenReturn(Optional.of(0.00214));

        mockMvc.perform(get("/api/prompts/{promptSpecId}", promptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executions[0].id").value("exec-priced"))
                .andExpect(jsonPath("$.executions[0].tokensIn").value(100))
                .andExpect(jsonPath("$.executions[0].tokensOut").value(200))
                .andExpect(jsonPath("$.executions[0].costUsd").value(0.00214));
    }

    @Test
    void getByIdOmitsCostUsdWhenPricingServiceReturnsEmpty() throws Exception {
        // Unknown models (or tokens we can't price) yield Optional.empty() from
        // ModelPricingService — the field must be OMITTED from the response so
        // the UI hides the chip rather than rendering a misleading $0.00.
        String promptId = "prompt-unknown-model";
        ChatCompletionRequest requestPayload = ChatCompletionRequest.builder()
                .withVendor("acme")
                .withModel("unknown-model-x")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("Ping")
                                .build()))
                .withParameters(Map.of())
                .build();

        Execution priced = new Execution(
                "exec-unpriced",
                Instant.parse("2026-05-12T12:00:00Z"),
                new ChatCompletionResponse(10L, null, "ok"),
                null,
                null,
                500L,
                100,
                200,
                null,
                null,
                "1",
                null,
                true,
                null);

        PromptSpec stored = PromptSpec.builder()
                .withGroup("support")
                .withName("unknown-model")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("desc")
                .withRequest(requestPayload)
                .build()
                .withId(promptId)
                .withExecutions(List.of(priced));

        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));
        when(modelPricingService.computeCost("unknown-model-x", 100, 200))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/prompts/{promptSpecId}", promptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executions[0].id").value("exec-unpriced"))
                .andExpect(jsonPath("$.executions[0].costUsd").doesNotExist());
    }

    @Test
    void getByIdOmitsCostUsdWhenTokenCountsAreNull() throws Exception {
        // Older executions captured before token tracking pre-date the
        // pricing inputs. The view layer must not invent a cost for them.
        String promptId = "prompt-legacy";
        ChatCompletionRequest requestPayload = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of())
                .withParameters(Map.of())
                .build();

        Execution legacy = new Execution(
                "exec-legacy",
                Instant.parse("2026-05-12T12:00:00Z"),
                new ChatCompletionResponse(10L, null, "ok"),
                null,
                null);

        PromptSpec stored = PromptSpec.builder()
                .withGroup("support")
                .withName("legacy")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("desc")
                .withRequest(requestPayload)
                .build()
                .withId(promptId)
                .withExecutions(List.of(legacy));

        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));
        when(modelPricingService.computeCost("gpt-4o", null, null))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/prompts/{promptSpecId}", promptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executions[0].id").value("exec-legacy"))
                .andExpect(jsonPath("$.executions[0].costUsd").doesNotExist())
                .andExpect(jsonPath("$.executions[0].cost").doesNotExist());
    }

    @Test
    void executePromptReturnsBadRequestForRecoverableExecutionErrors() throws Exception {
        ChatCompletionRequest requestPayload = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("Ping")
                                .build()
                ))
                .withParameters(Map.of())
                .build();

        PromptSpec prompt = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-a")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("desc")
                .withRequest(requestPayload)
                .build()
                .withId("prompt-a");

        when(promptExecutor.runPromptAndAttachResponse(any(PromptSpec.class)))
                .thenThrow(new IllegalStateException("No Prompt gateway available for vendor 'openai' and model 'gpt-4o'"));

        ExecutePromptRequest executePromptRequest = new ExecutePromptRequest(prompt);

        mockMvc.perform(post("/api/prompts/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(executePromptRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // #127: handler returns curated text from PromptExecutorFailureClassifierResolver,
                // never the raw cause.getMessage(). Unclassified causes fall back to the
                // resolver's UNKNOWN default → "Prompt execution failed".
                .andExpect(jsonPath("$.detail").value("Prompt execution failed"))
                .andExpect(jsonPath("$.detail").value(not(containsString("No Prompt gateway"))))
                .andExpect(jsonPath("$.code").value("UNKNOWN"));
    }

    @Test
    void executePromptReturnsInternalServerErrorForUnexpectedExecutionErrors() throws Exception {
        ChatCompletionRequest requestPayload = ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4o")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("Ping")
                                .build()
                ))
                .withParameters(Map.of())
                .build();

        PromptSpec prompt = PromptSpec.builder()
                .withGroup("support")
                .withName("prompt-a")
                .withVersion("1.0.0")
                .withRevision(1)
                .withDescription("desc")
                .withRequest(requestPayload)
                .build()
                .withId("prompt-a");

        when(promptExecutor.runPromptAndAttachResponse(any(PromptSpec.class)))
                .thenThrow(new RuntimeException("Unexpected execution failure"));

        ExecutePromptRequest executePromptRequest = new ExecutePromptRequest(prompt);

        mockMvc.perform(post("/api/prompts/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(executePromptRequest)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // #127: curated text from classifier, raw cause message not echoed.
                .andExpect(jsonPath("$.detail").value("Prompt execution failed"))
                .andExpect(jsonPath("$.detail").value(not(containsString("Unexpected execution failure"))))
                .andExpect(jsonPath("$.code").value("UNKNOWN"));
    }

    @Test
    void updatePromptSpecMapsEvaluationExtensions() throws Exception {
        String promptId = "prompt-123";

        PromptSpecCreationRequest request = new PromptSpecCreationRequest();
        request.setId(promptId);
        request.setName("Prompt");
        request.setGroup("group");
        request.setDescription("desc");
        request.setMessages(List.of());

        VendorAndModel vendorAndModel = new VendorAndModel();
        vendorAndModel.setVendorName("openai");
        vendorAndModel.setModel("gpt-4");
        request.setVendorAndModel(vendorAndModel);

        PromptSpecCreationRequest.Request apiRequest = new PromptSpecCreationRequest.Request();
        apiRequest.setMessages(List.of());
        apiRequest.setParameters(new PromptSpecCreationRequest.Parameters());
        request.setRequest(apiRequest);

        ObjectNode evaluationNode = objectMapper.createObjectNode();
        ObjectNode definitionNode = objectMapper.createObjectNode();
        definitionNode.put("evaluator", "semantic-match");
        definitionNode.put("type", "rubric");
        definitionNode.put("description", "score relevance");
        evaluationNode.set("spec", objectMapper.createObjectNode()
                .set("evaluations", objectMapper.createArrayNode().add(definitionNode)));
        request.setExtensions(Map.of("x-evaluation", evaluationNode));

        when(promptLifecycleFacade.updatePrompt(eq(promptId), any(PromptSpec.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        mockMvc.perform(put("/api/prompts/{promptSpecId}", promptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));

        ArgumentCaptor<PromptSpec> promptCaptor = ArgumentCaptor.forClass(PromptSpec.class);
        verify(promptLifecycleFacade).updatePrompt(eq(promptId), promptCaptor.capture());

        PromptSpec updated = promptCaptor.getValue();
        assertThat(updated.getEvaluationSpec()).isNotNull();
        assertThat(updated.getEvaluationSpec().getEvaluations()).hasSize(1);
        assertThat(updated.getEvaluationSpec().getEvaluations().get(0))
                .isInstanceOf(PromptEvaluationDefinition.class);

        PromptEvaluationDefinition evaluation =
                (PromptEvaluationDefinition) updated.getEvaluationSpec().getEvaluations().get(0);
        assertThat(evaluation.getEvaluator()).isEqualTo("semantic-match");
        assertThat(evaluation.getType()).isEqualTo("rubric");
        assertThat(evaluation.getDescription()).isEqualTo("score relevance");
    }

    @Test
    void createPromptSpecIncludesUiFields() throws Exception {
        PromptSpecCreationRequest request = new PromptSpecCreationRequest();
        request.setName("new-prompt");
        request.setGroup("support");
        request.setDescription("new description");
        request.setVersion("1.2.3");
        request.setPlaceholderStartPattern("<<");
        request.setPlaceholderEndPattern(">>");
        request.setPlaceholder(Map.of("customer", "Alice"));
        request.setGitHubRepo("https://github.com/org/repo");

        VendorAndModel vendorAndModel = new VendorAndModel();
        vendorAndModel.setVendorName("openai");
        vendorAndModel.setModel("gpt-4o");
        vendorAndModel.setEndpoint("https://api.example.com/v1");
        request.setVendorAndModel(vendorAndModel);

        PromptSpecCreationRequest.Parameters params = new PromptSpecCreationRequest.Parameters();
        params.setTemperature(0.7);
        params.setMaxTokens(250);

        PromptSpecCreationRequest.Request requestPayload = new PromptSpecCreationRequest.Request();
        requestPayload.setType("chat/completion");
        requestPayload.setVendor("openai");
        requestPayload.setModel("gpt-4o");
        requestPayload.setUrl("https://override.example.com/v1");
        requestPayload.setModelSnapshot("2024-05-01");
        requestPayload.setParameters(params);

        Message message = new Message();
        message.setRole(Message.Role.USER);
        message.setContent("Hello");
        request.setMessages(List.of(message));
        requestPayload.setMessages(List.of(message));
        request.setRequest(requestPayload);

        when(promptLifecycleFacade.createPromptSpec(any(PromptSpec.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/prompts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));

        ArgumentCaptor<PromptSpec> promptCaptor = ArgumentCaptor.forClass(PromptSpec.class);
        verify(promptLifecycleFacade).createPromptSpec(promptCaptor.capture());

        PromptSpec created = promptCaptor.getValue();
        assertThat(created.getDescription()).isEqualTo("new description");
        assertThat(created.getVersion()).isEqualTo("1.2.3");
        assertThat(created.getRepositoryUrl()).isEqualTo("https://github.com/org/repo");
        assertThat(created.getPlaceholders().getStartPattern()).isEqualTo("<<");
        assertThat(created.getPlaceholders().getEndPattern()).isEqualTo(">>");
        assertThat(created.getPlaceholders().getList())
                .extracting(PromptSpec.Placeholder::getName, PromptSpec.Placeholder::getValue)
                .containsExactly(tuple("customer", "Alice"));

        ChatCompletionRequest createdRequest = (ChatCompletionRequest) created.getRequest();
        assertThat(createdRequest.getVendor()).isEqualTo("openai");
        assertThat(createdRequest.getModel()).isEqualTo("gpt-4o");
        assertThat(createdRequest.getUrl()).isEqualTo("https://override.example.com/v1");
        assertThat(createdRequest.getType()).isEqualTo("chat/completion");
        assertThat(createdRequest.getModelSnapshot()).isEqualTo("2024-05-01");
        assertThat(createdRequest.getParameters()).containsEntry("temperature", 0.7);
        assertThat(createdRequest.getParameters()).containsEntry("maxTokens", 250);
        assertThat(createdRequest.getMessages())
                .extracting(ChatCompletionRequest.Message::getContent)
                .containsExactly("Hello");
    }

    /**
     * Ensures prompt creation is case-insensitive: creating the same (group,name) twice using
     * different casing must yield a 409 Conflict on the second request.
     */
    @Test
    void createPromptSpecShouldRejectCaseInsensitiveDuplicate() throws Exception {
        PromptSpecCreationRequest request = new PromptSpecCreationRequest();
        request.setName("Welcome");
        request.setGroup("Support");
        request.setDescription("desc");
        request.setMessages(List.of());

        PromptSpec created = PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion("1")
                .withRevision(1)
                .withDescription("desc")
                .withRequest(null)
                .build();

        when(promptLifecycleFacade.createPromptSpec(any(PromptSpec.class)))
                .thenReturn(created)
                .thenThrow(new PromptSpecAlreadyExistsException("support", "welcome"));

        mockMvc.perform(post("/api/prompts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        PromptSpecCreationRequest second = new PromptSpecCreationRequest();
        second.setName("welcome");
        second.setGroup("support");
        second.setDescription("desc");
        second.setMessages(List.of());

        mockMvc.perform(post("/api/prompts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isConflict());

        verify(promptLifecycleFacade, times(2)).createPromptSpec(any(PromptSpec.class));
    }

    @Test
    void getRevisionsByGroupAndNameReturnsListNewestFirst() throws Exception {
        PromptSpec snapshot = PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion("1.0.0")
                .withRevision(2)
                .withDescription("snapshot")
                .withRequest(null)
                .build()
                .withId("support/welcome");

        Revision newer = new Revision(
                "r2",
                null,
                "f0d51b1c2c1f5fa9d3a3b2e8f7e3a1d4c5b6a7e8",
                "Jane Doe",
                Instant.parse("2026-04-01T10:00:00Z"),
                "Tweak welcome copy",
                Revision.Kind.EDIT,
                snapshot
        );
        Revision older = new Revision(
                "r1",
                null,
                "a1b2c3d4e5f6071829304050607080900a0b0c0d",
                "Jane Doe",
                Instant.parse("2026-03-15T09:00:00Z"),
                "Initial welcome prompt",
                Revision.Kind.ADD,
                snapshot
        );

        when(promptStore.listRevisions("support", "welcome")).thenReturn(List.of(newer, older));

        mockMvc.perform(get("/api/prompts/{group}/{name}/revisions", "support", "welcome"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].rev").value("r2"))
                .andExpect(jsonPath("$[0].sha").value(newer.sha()))
                .andExpect(jsonPath("$[0].kind").value("edit"))
                .andExpect(jsonPath("$[0].when").value("2026-04-01T10:00:00Z"))
                .andExpect(jsonPath("$[0].author").value("Jane Doe"))
                .andExpect(jsonPath("$[0].msg").value("Tweak welcome copy"))
                .andExpect(jsonPath("$[0].tag").doesNotExist())
                .andExpect(jsonPath("$[0].spec.group").value("support"))
                .andExpect(jsonPath("$[0].spec.name").value("welcome"))
                .andExpect(jsonPath("$[1].rev").value("r1"))
                .andExpect(jsonPath("$[1].kind").value("add"));
    }

    @Test
    void getRevisionsByPromptIdSplitsGroupAndName() throws Exception {
        Revision rev = new Revision(
                "r1",
                "v1.0.0",
                "0123456789abcdef0123456789abcdef01234567",
                "Jane Doe",
                Instant.parse("2026-03-15T09:00:00Z"),
                "Initial",
                Revision.Kind.ADD,
                null
        );
        when(promptStore.listRevisions("support", "welcome")).thenReturn(List.of(rev));

        mockMvc.perform(get("/api/prompts/{promptSpecId}/revisions", "support/welcome"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tag").value("v1.0.0"))
                .andExpect(jsonPath("$[0].spec").doesNotExist());

        verify(promptStore).listRevisions("support", "welcome");
    }

    @Test
    void getRevisionsReturnsNotFoundWhenStoreReturnsEmpty() throws Exception {
        when(promptStore.listRevisions("support", "missing")).thenReturn(List.of());

        mockMvc.perform(get("/api/prompts/{group}/{name}/revisions", "support", "missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRepoHistoryReturnsOlderRevisionExecutionsSortedNewestFirst() throws Exception {
        String promptId = "support/welcome";
        Execution older1 = newExecution("old-1", "r1", true, Instant.parse("2026-04-01T00:00:00Z"));
        Execution older2 = newExecution("old-2", "r1", true, Instant.parse("2026-04-02T00:00:00Z"));
        Execution latestRun = newExecution("latest-1", "r2", true, Instant.parse("2026-04-10T00:00:00Z"));
        PromptSpec olderVersion = repoHistoryFixture(promptId, "0.1.0", 1, List.of(older1, older2));
        PromptSpec latestVersion = repoHistoryFixture(promptId, "0.2.0", 2, List.of(latestRun));

        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(latestVersion));
        when(promptStore.listVersions(promptId)).thenReturn(List.of(olderVersion, latestVersion));

        mockMvc.perform(get("/api/prompts/{promptSpecId}/history", promptId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("old-2"))
                .andExpect(jsonPath("$.items[1].id").value("old-1"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void getRepoHistoryReturnsEmptyEnvelopeForSingleVersionPrompt() throws Exception {
        String promptId = "support/welcome";
        PromptSpec onlyVersion = repoHistoryFixture(promptId, "0.1.0", 1,
                List.of(newExecution("only", "r1", true, Instant.parse("2026-04-01T00:00:00Z"))));
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(onlyVersion));
        when(promptStore.listVersions(promptId)).thenReturn(List.of(onlyVersion));

        mockMvc.perform(get("/api/prompts/{promptSpecId}/history", promptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void getRepoHistoryClampsNonPositivePageToFirstPage() throws Exception {
        String promptId = "support/welcome";
        Execution older1 = newExecution("old-1", "r1", true, Instant.parse("2026-04-01T00:00:00Z"));
        Execution older2 = newExecution("old-2", "r1", true, Instant.parse("2026-04-02T00:00:00Z"));
        PromptSpec older = repoHistoryFixture(promptId, "0.1.0", 1, List.of(older1, older2));
        PromptSpec latestVersion = repoHistoryFixture(promptId, "0.2.0", 2, List.of());

        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(latestVersion));
        when(promptStore.listVersions(promptId)).thenReturn(List.of(older, latestVersion));

        mockMvc.perform(get("/api/prompts/{promptSpecId}/history", promptId).queryParam("page", "-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void getRepoHistoryByGroupAndNameAliasDelegatesToCanonicalEndpoint() throws Exception {
        String promptId = "support/welcome";
        Execution older = newExecution("old-1", "r1", true, Instant.parse("2026-04-01T00:00:00Z"));
        PromptSpec olderVersion = repoHistoryFixture(promptId, "0.1.0", 1, List.of(older));
        PromptSpec latestVersion = repoHistoryFixture(promptId, "0.2.0", 2, List.of());

        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(latestVersion));
        when(promptStore.listVersions(promptId)).thenReturn(List.of(olderVersion, latestVersion));

        mockMvc.perform(get("/api/prompts/{group}/{name}/history", "support", "welcome"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("old-1"));
    }

    @Test
    void getRepoHistoryReturnsNotFoundForUnknownPrompt() throws Exception {
        when(promptStore.getLatestVersion("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/prompts/{promptSpecId}/history", "missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRevisionsReturnsBadRequestWhenStoreRejectsSegment() throws Exception {
        when(promptStore.listRevisions(eq("support"), eq("..")))
                .thenThrow(new IllegalArgumentException("name contains invalid path segment"));

        mockMvc.perform(get("/api/prompts/{group}/{name}/revisions", "support", ".."))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getRepoHistoryFiltersByRevision() throws Exception {
        String promptId = "support/welcome";
        Execution r1a = newExecution("r1a", "r1", true, Instant.parse("2026-04-01T00:00:00Z"));
        Execution r1b = newExecution("r1b", "r1", true, Instant.parse("2026-04-02T00:00:00Z"));
        Execution r2a = newExecution("r2a", "r2", true, Instant.parse("2026-04-03T00:00:00Z"));
        PromptSpec v1 = repoHistoryFixture(promptId, "0.1.0", 1, List.of(r1a, r1b));
        PromptSpec v2 = repoHistoryFixture(promptId, "0.2.0", 2, List.of(r2a));
        PromptSpec latestVersion = repoHistoryFixture(promptId, "0.3.0", 3, List.of());

        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(latestVersion));
        when(promptStore.listVersions(promptId)).thenReturn(List.of(v1, v2, latestVersion));

        mockMvc.perform(get("/api/prompts/{promptSpecId}/history", promptId).queryParam("revision", "r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("r1b"))
                .andExpect(jsonPath("$.items[1].id").value("r1a"));
    }

    @Test
    void getRepoHistoryFiltersByStatus() throws Exception {
        String promptId = "support/welcome";
        Execution ok = newExecution("ok", "r1", true, Instant.parse("2026-04-01T00:00:00Z"));
        Execution fail = newExecution("fail", "r1", false, Instant.parse("2026-04-02T00:00:00Z"));
        PromptSpec older = repoHistoryFixture(promptId, "0.1.0", 1, List.of(ok, fail));
        PromptSpec latestVersion = repoHistoryFixture(promptId, "0.2.0", 2, List.of());

        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(latestVersion));
        when(promptStore.listVersions(promptId)).thenReturn(List.of(older, latestVersion));

        mockMvc.perform(get("/api/prompts/{promptSpecId}/history", promptId).queryParam("status", "fail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("fail"));
    }

    @Test
    void getRepoHistoryRespectsPageSizeAndPaginates() throws Exception {
        String promptId = "support/welcome";
        java.util.List<Execution> seventyFive = new java.util.ArrayList<>();
        for (int i = 0; i < 75; i++) {
            seventyFive.add(newExecution("e-" + i, "r1", true,
                    Instant.parse("2026-04-01T00:00:00Z").plusSeconds(i)));
        }
        PromptSpec older = repoHistoryFixture(promptId, "0.1.0", 1, seventyFive);
        PromptSpec latestVersion = repoHistoryFixture(promptId, "0.2.0", 2, List.of());

        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(latestVersion));
        when(promptStore.listVersions(promptId)).thenReturn(List.of(older, latestVersion));

        mockMvc.perform(get("/api/prompts/{promptSpecId}/history", promptId)
                        .queryParam("page", "1").queryParam("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(50))
                .andExpect(jsonPath("$.total").value(75))
                .andExpect(jsonPath("$.hasMore").value(true));

        mockMvc.perform(get("/api/prompts/{promptSpecId}/history", promptId)
                        .queryParam("page", "2").queryParam("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(25))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void getRepoHistoryRejectsUnknownStatus() throws Exception {
        String promptId = "support/welcome";
        PromptSpec latestVersion = repoHistoryFixture(promptId, "0.1.0", 1, List.of());
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(latestVersion));
        when(promptStore.listVersions(promptId)).thenReturn(List.of(latestVersion));

        mockMvc.perform(get("/api/prompts/{promptSpecId}/history", promptId).queryParam("status", "maybe"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getRevisionsByPromptIdReturnsBadRequestWhenIdIsMalformed() throws Exception {
        mockMvc.perform(get("/api/prompts/{promptSpecId}/revisions", "no-slash-here"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getRevisionsExposesWeakEtagDerivedFromNewestSha() throws Exception {
        Revision newer = new Revision(
                "r2",
                null,
                "f0d51b1c2c1f5fa9d3a3b2e8f7e3a1d4c5b6a7e8",
                "Jane Doe",
                Instant.parse("2026-04-01T10:00:00Z"),
                "Tweak",
                Revision.Kind.EDIT,
                null
        );
        Revision older = new Revision(
                "r1",
                null,
                "a1b2c3d4e5f6071829304050607080900a0b0c0d",
                "Jane Doe",
                Instant.parse("2026-03-15T09:00:00Z"),
                "Initial",
                Revision.Kind.ADD,
                null
        );
        when(promptStore.listRevisions("support", "welcome")).thenReturn(List.of(newer, older));

        mockMvc.perform(get("/api/prompts/{group}/{name}/revisions", "support", "welcome"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "W/\"" + newer.sha() + "\""));
    }

    @Test
    void getRevisionsReturnsNotModifiedWhenIfNoneMatchEqualsCurrentEtag() throws Exception {
        Revision newer = new Revision(
                "r2", null, "f0d51b1c2c1f5fa9d3a3b2e8f7e3a1d4c5b6a7e8",
                "Jane Doe", Instant.parse("2026-04-01T10:00:00Z"),
                "Tweak", Revision.Kind.EDIT, null
        );
        when(promptStore.listRevisions("support", "welcome")).thenReturn(List.of(newer));

        mockMvc.perform(get("/api/prompts/{group}/{name}/revisions", "support", "welcome")
                        .header("If-None-Match", "W/\"" + newer.sha() + "\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "W/\"" + newer.sha() + "\""));
    }

    @Test
    void getRevisionsReturnsNotModifiedForStrongFormOfWeakEtag() throws Exception {
        Revision newer = new Revision(
                "r2", null, "f0d51b1c2c1f5fa9d3a3b2e8f7e3a1d4c5b6a7e8",
                "Jane Doe", Instant.parse("2026-04-01T10:00:00Z"),
                "Tweak", Revision.Kind.EDIT, null
        );
        when(promptStore.listRevisions("support", "welcome")).thenReturn(List.of(newer));

        // Client may have stripped the W/ prefix; the opaque tag still matches.
        mockMvc.perform(get("/api/prompts/{group}/{name}/revisions", "support", "welcome")
                        .header("If-None-Match", "\"" + newer.sha() + "\""))
                .andExpect(status().isNotModified());
    }

    @Test
    void getRevisionsReturnsNotModifiedForWildcardIfNoneMatch() throws Exception {
        Revision newer = new Revision(
                "r2", null, "abc", "x", Instant.now(), "m", Revision.Kind.ADD, null);
        when(promptStore.listRevisions("support", "welcome")).thenReturn(List.of(newer));

        mockMvc.perform(get("/api/prompts/{group}/{name}/revisions", "support", "welcome")
                        .header("If-None-Match", "*"))
                .andExpect(status().isNotModified());
    }

    @Test
    void getRevisionsReturnsFullPayloadWhenIfNoneMatchDoesNotMatch() throws Exception {
        Revision newer = new Revision(
                "r2", null, "f0d51b1c2c1f5fa9d3a3b2e8f7e3a1d4c5b6a7e8",
                "Jane Doe", Instant.parse("2026-04-01T10:00:00Z"),
                "Tweak", Revision.Kind.EDIT, null
        );
        when(promptStore.listRevisions("support", "welcome")).thenReturn(List.of(newer));

        mockMvc.perform(get("/api/prompts/{group}/{name}/revisions", "support", "welcome")
                        .header("If-None-Match", "W/\"deadbeefdeadbeefdeadbeefdeadbeefdeadbeef\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(header().string("ETag", "W/\"" + newer.sha() + "\""));
    }

    @Test
    void getRepoHistoryClampsExcessivePageSizeToMaximum() throws Exception {
        String promptId = "support/welcome";
        Execution sample = newExecution("e", "r1", true, Instant.parse("2026-04-01T00:00:00Z"));
        PromptSpec older = repoHistoryFixture(promptId, "0.1.0", 1, List.of(sample));
        PromptSpec latestVersion = repoHistoryFixture(promptId, "0.2.0", 2, List.of());

        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(latestVersion));
        when(promptStore.listVersions(promptId)).thenReturn(List.of(older, latestVersion));

        mockMvc.perform(get("/api/prompts/{promptSpecId}/history", promptId).queryParam("pageSize", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSize").value(RepoHistoryAssembler.MAX_PAGE_SIZE));
    }

    @Test
    void retryPushRepublishesPromptExecutedEventAndReturnsAccepted() throws Exception {
        // Issue #361: the Retry-push endpoint re-fires only the deferred amend+push by
        // republishing PromptExecutedEvent against the stored spec — the LLM is NOT
        // re-invoked, so the executor must remain untouched.
        String promptId = "support/welcome";
        PromptSpec stored = baseSpec(promptId);
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.of(stored));

        mockMvc.perform(post("/api/prompts/{promptSpecId}/push/retry", promptId))
                .andExpect(status().isAccepted());

        assertThat(applicationEvents).isNotNull();
        List<dev.promptlm.domain.events.PromptExecutedEvent> executedEvents =
                applicationEvents.stream(dev.promptlm.domain.events.PromptExecutedEvent.class).toList();
        assertThat(executedEvents).hasSize(1);
        assertThat(executedEvents.get(0).promptSpec()).isSameAs(stored);

        // No LLM re-run on the push-retry path.
        verifyNoInteractions(promptExecutor);
    }

    @Test
    void retryPushReturnsNotFoundWhenPromptIsMissing() throws Exception {
        String promptId = "support/missing";
        when(promptStore.getLatestVersion(promptId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/prompts/{promptSpecId}/push/retry", promptId))
                .andExpect(status().isNotFound());

        if (applicationEvents != null) {
            assertThat(applicationEvents.stream(dev.promptlm.domain.events.PromptExecutedEvent.class))
                    .isEmpty();
        }
        verifyNoInteractions(promptExecutor);
    }

    private static PromptSpec repoHistoryFixture(String id, String version, int revision, List<Execution> executions) {
        return PromptSpec.builder()
                .withGroup("support")
                .withName("welcome")
                .withVersion(version)
                .withRevision(revision)
                .withDescription("desc")
                .withRequest(ChatCompletionRequest.builder()
                        .withVendor("openai")
                        .withModel("gpt-4o")
                        .withMessages(List.of())
                        .build())
                .withExecutions(executions)
                .build()
                .withId(id);
    }

    private static Execution newExecution(String id, String revision, boolean ok, Instant timestamp) {
        Execution e = new Execution();
        e.setId(id);
        e.setRevision(revision);
        e.setOk(ok);
        e.setTimestamp(timestamp);
        return e;
    }

    private Path initializeRepositoryWithPromptCommit(Path tempDir, Instant commitInstant) throws Exception {
        Path repository = tempDir.resolve("repo");
        Files.createDirectories(repository);

        try (Git git = Git.init()
                .setInitialBranch("main")
                .setDirectory(repository.toFile())
                .call()) {
            Path promptFile = repository.resolve("prompts/support/welcome/promptlm.yml");
            Files.createDirectories(promptFile.getParent());
            Files.writeString(promptFile, "id: welcome\nname: welcome\ngroup: support\n");

            git.add()
                    .addFilepattern("prompts/support/welcome/promptlm.yml")
                    .call();

            PersonIdent ident = new PersonIdent(
                    "PromptLM Test",
                    "promptlm@example.com",
                    Date.from(commitInstant),
                    TimeZone.getTimeZone("UTC"));

            git.commit()
                    .setMessage("Add prompt")
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .call();
        }

        return repository;
    }
}
