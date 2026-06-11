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

package dev.promptlm.lifecycle.application;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import dev.promptlm.domain.promptspec.ChatCompletionRequest;
import dev.promptlm.domain.promptspec.ChatCompletionResponse;
import dev.promptlm.domain.promptspec.EvaluationResult;
import dev.promptlm.domain.promptspec.EvaluationResults;
import dev.promptlm.domain.promptspec.EvaluationStatus;
import dev.promptlm.domain.promptspec.Execution;
import dev.promptlm.domain.promptspec.PromptSpec;
import dev.promptlm.domain.promptspec.ReleaseMetadata;
import dev.promptlm.lifecycle.audit.NoOpReleaseAuditContext;
import dev.promptlm.lifecycle.audit.ReleaseAuditContext;
import dev.promptlm.lifecycle.audit.ReleaseAuditEvent;
import dev.promptlm.lifecycle.audit.ReleaseAuditLogger;
import dev.promptlm.lifecycle.audit.ReleaseAuditOutcome;
import dev.promptlm.release.OnInfraFailure;
import dev.promptlm.release.PreReleaseExecuteGate;
import dev.promptlm.release.PreReleaseInfrastructureFailure;
import dev.promptlm.release.PreReleasePromptFailure;
import dev.promptlm.release.PromptReleaseException;
import dev.promptlm.release.PromptReleasePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import dev.promptlm.domain.events.PromptCreatedEvent;
import dev.promptlm.domain.events.PromptReleaseRequestedEvent;
import dev.promptlm.domain.events.PromptReleasedEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultPromptLifecycleServiceTest {

    private static final String GROUP = "group";
    private static final String NAME = "name";
    private static final String PROMPT_ID = GROUP + "/" + NAME;

    @Mock
    private PromptStorePort repository;

    @Mock
    private PromptTemplatePort template;

    @Mock
    private PromptIdGeneratorPort idGenerator;

    @Mock
    private PromptLifecycleEventPublisher eventPublisher;

    @Mock
    private PromptReleasePolicy releasePolicy;

    @Mock
    private PreReleaseExecuteGate preReleaseExecuteGate;

    @Mock
    private ReleaseAuditLogger auditLogger;

    private final ReleaseAuditContext auditContext = new NoOpReleaseAuditContext();

    private DefaultPromptLifecycleService service;

    private PromptSpec basePromptSpec;

    @BeforeEach
    void setUp() {
        service = new DefaultPromptLifecycleService(
                repository,
                template,
                idGenerator,
                eventPublisher,
                releasePolicy,
                preReleaseExecuteGate,
                auditLogger,
                auditContext);
        basePromptSpec = PromptSpec.builder()
                .withGroup(GROUP)
                .withName(NAME)
                .withVersion("1.0.0-SNAPSHOT")
                .withRevision(1)
                .withDescription("desc")
                .withRequest(ChatCompletionRequest.builder()
                        .withVendor("openai")
                        .withModel("gpt-4")
                        .withMessages(List.of())
                        .build())
                .build();
    }

    @Test
    void createPromptAssignsIdPublishesEventAndReturnsSpec() {
        PromptSpec rendered = basePromptSpec;
        PromptSpec withId = rendered.withId(PROMPT_ID);

        when(repository.findPromptSpec(GROUP, NAME)).thenReturn(Optional.empty());
        when(repository.findPromptSpecTemplate(GROUP)).thenReturn("template");
        when(template.render(eq("template"), eq(GROUP), eq(NAME), any(), any(), any())).thenReturn(rendered);
        when(repository.commitLocally(withId)).thenReturn(withId);

        PromptSpec result = service.createPrompt(GROUP, Map.of(), NAME, List.of(), new PromptSpec.Placeholders(), Map.of());

        assertThat(result).isEqualTo(withId);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(PromptCreatedEvent.class);
        PromptCreatedEvent event = (PromptCreatedEvent) eventCaptor.getValue();
        assertThat(event.promptSpec()).isEqualTo(withId);

        verify(repository).findPromptSpec(GROUP, NAME);
        verify(repository).findPromptSpecTemplate(GROUP);
        verify(template).render(eq("template"), eq(GROUP), eq(NAME), any(), any(), any());
    }

    @Test
    void createPromptThrowsWhenPromptAlreadyExists() {
        when(repository.findPromptSpec(GROUP, NAME)).thenReturn(Optional.of(basePromptSpec));

        assertThatThrownBy(() -> service.createPrompt(GROUP, Map.of(), NAME, List.of(), new PromptSpec.Placeholders(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        verify(repository).findPromptSpec(GROUP, NAME);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(template, idGenerator, eventPublisher);
    }

    /**
     * Ensures prompt creation is case-insensitive by normalizing group and name to lowercase
     * before checking for duplicates and generating IDs.
     */
    @Test
    void createPromptSpecNormalizesGroupAndNameToLowercase() {
        PromptSpec mixedCase = basePromptSpec
                .withGroup("Group")
                .withName("Name")
                .withId(null);

        when(repository.findPromptSpec(GROUP, NAME)).thenReturn(Optional.empty());
        when(idGenerator.generateId(eq(GROUP), eq(NAME), any())).thenReturn(PROMPT_ID);
        when(repository.commitLocally(any(PromptSpec.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createPromptSpec(mixedCase);

        ArgumentCaptor<PromptSpec> storedCaptor = ArgumentCaptor.forClass(PromptSpec.class);
        verify(repository).findPromptSpec(GROUP, NAME);
        verify(idGenerator).generateId(eq(GROUP), eq(NAME), any());
        verify(repository).commitLocally(storedCaptor.capture());

        PromptSpec stored = storedCaptor.getValue();
        assertThat(stored.getGroup()).isEqualTo(GROUP);
        assertThat(stored.getName()).isEqualTo(NAME);
    }

    @Test
    void createDefaultPromptSpecReturnsBlankDraftTemplate() {
        // Issue #309 — the New-prompt editor must open blank. The template carries
        // structural defaults (placeholder delimiters, parameter defaults, empty
        // system/user message slots) but no demo content the user would have to
        // overwrite (and could accidentally save into their repo verbatim).
        PromptSpec templateSpec = service.createDefaultPromptSpec();

        assertThat(templateSpec.getGroup()).isEmpty();
        assertThat(templateSpec.getName()).isEmpty();
        assertThat(templateSpec.getDescription()).isEmpty();

        ChatCompletionRequest request = (ChatCompletionRequest) templateSpec.getRequest();
        assertThat(request.getVendor()).isEmpty();
        assertThat(request.getModel()).isEmpty();
        assertThat(request.getMessages())
                .extracting(ChatCompletionRequest.Message::getRole, ChatCompletionRequest.Message::getContent)
                .containsExactly(
                        tuple("system", ""),
                        tuple("user", "")
                );
        assertThat(request.getParameters()).containsEntry("maxTokens", 1024);
        assertThat(request.getParameters()).containsEntry("stream", false);

        assertThat(templateSpec.getPlaceholders()).isNotNull();
        assertThat(templateSpec.getPlaceholders().getStartPattern()).isEqualTo("{{");
        assertThat(templateSpec.getPlaceholders().getEndPattern()).isEqualTo("}}");
        assertThat(templateSpec.getPlaceholders().getList()).isEmpty();
    }

    @Test
    void updatePromptStoresPromptWithIncrementedRevision() {
        PromptSpec existing = basePromptSpec.withRevision(3);
        PromptSpec updating = existing.withRequest(ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4")
                .withMessages(List.of(ChatCompletionRequest.Message.builder()
                        .withRole("user")
                        .withContent("new-message")
                        .build()))
                .build());

        assertThat(updating).isEqualTo(existing);
        assertThat(updating.hasSemanticChangesComparedTo(existing)).isTrue();

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.commitLocally(any(PromptSpec.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PromptSpec result = service.updatePrompt(PROMPT_ID, updating);

        assertThat(((ChatCompletionRequest) result.getRequest()).getMessages())
                .extracting(ChatCompletionRequest.Message::getContent)
                .containsExactly("new-message");
        assertThat(result.getRevision()).isEqualTo(4);

        verify(repository).getLatestVersion(PROMPT_ID);
        verify(repository).commitLocally(result);
    }

    @Test
    void updatePromptStoresPromptWithoutRevisionIncreaseWhenOnlyMetadataChanged() {
        ChatCompletionResponse existingResponse = new ChatCompletionResponse(12L, null, "already-stored");
        PromptSpec existing = basePromptSpec
                .withRevision(3)
                .withResponse(existingResponse);
        PromptSpec updating = basePromptSpec
                .withRevision(3)
                .withDescription("new-desc");

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.commitLocally(any(PromptSpec.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PromptSpec result = service.updatePrompt(PROMPT_ID, updating);

        assertThat(result.getDescription()).isEqualTo("new-desc");
        assertThat(result.getRevision()).isEqualTo(3);
        assertThat(result.getResponse()).isEqualTo(existingResponse);

        verify(repository).getLatestVersion(PROMPT_ID);
        verify(repository).commitLocally(result);
    }

    @Test
    void updatePromptStoresProvidedResponseWithoutRevisionIncrease() {
        PromptSpec existing = basePromptSpec.withRevision(3);
        ChatCompletionResponse capturedResponse = new ChatCompletionResponse(45L, null, "captured");
        PromptSpec updating = existing.withResponse(capturedResponse);

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.commitLocally(any(PromptSpec.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PromptSpec result = service.updatePrompt(PROMPT_ID, updating);

        assertThat(result.getRevision()).isEqualTo(3);
        assertThat(result.getResponse()).isEqualTo(capturedResponse);

        verify(repository).getLatestVersion(PROMPT_ID);
        verify(repository).commitLocally(result);
    }

    @Test
    void updatePromptPreservesExtensionsWhenUpdateOmitsExtensions() {
        ObjectNode extensionNode = JsonNodeFactory.instance.objectNode()
                .put("foo", "bar");
        PromptSpec existing = basePromptSpec
                .withRevision(3)
                .withExtensions(Map.of("x-legacy", extensionNode));
        PromptSpec updating = basePromptSpec
                .withRevision(3)
                .withDescription("new-desc");

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.commitLocally(any(PromptSpec.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PromptSpec result = service.updatePrompt(PROMPT_ID, updating);

        assertThat(result.getExtensions()).containsEntry("x-legacy", extensionNode);

        verify(repository).getLatestVersion(PROMPT_ID);
        verify(repository).commitLocally(result);
    }

    @Test
    void updatePromptMergesExtensionsWhenUpdateProvidesNewEntries() {
        ObjectNode legacyNode = JsonNodeFactory.instance.objectNode()
                .put("foo", "bar");
        ObjectNode newNode = JsonNodeFactory.instance.objectNode()
                .put("baz", "qux");
        PromptSpec existing = basePromptSpec
                .withRevision(3)
                .withExtensions(Map.of("x-legacy", legacyNode));
        PromptSpec updating = basePromptSpec
                .withRevision(3)
                .withExtensions(Map.of("x-new", newNode));

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.commitLocally(any(PromptSpec.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PromptSpec result = service.updatePrompt(PROMPT_ID, updating);

        assertThat(result.getExtensions())
                .containsEntry("x-legacy", legacyNode)
                .containsEntry("x-new", newNode);

        verify(repository).getLatestVersion(PROMPT_ID);
        verify(repository).commitLocally(result);
    }

    @Test
    void persistEvaluatedPromptThrowsWhenPromptIsNull() {
        assertThatThrownBy(() -> service.persistEvaluatedPrompt(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        verifyNoInteractions(repository, releasePolicy, eventPublisher, template, idGenerator);
    }

    @Test
    void persistEvaluatedPromptThrowsWhenPromptIdIsMissing() {
        PromptSpec withoutId = basePromptSpec.withId(null);

        assertThatThrownBy(() -> service.persistEvaluatedPrompt(withoutId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id must not be null or blank");

        verifyNoInteractions(repository, releasePolicy, eventPublisher, template, idGenerator);
    }

    @Test
    void persistEvaluatedPromptThrowsWhenPromptDoesNotExist() {
        PromptSpec evaluated = basePromptSpec
                .withId(PROMPT_ID)
                .withRevision(4)
                .withEvaluationResults(new EvaluationResults(List.of(), EvaluationStatus.EVALUATED_OK));
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.persistEvaluatedPrompt(evaluated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");

        verify(repository).getLatestVersion(PROMPT_ID);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void persistEvaluatedPromptStoresNewRevisionThroughRepository() {
        PromptSpec evaluated = basePromptSpec
                .withId(PROMPT_ID)
                .withRevision(4)
                .withEvaluationResults(new EvaluationResults(List.of(), EvaluationStatus.EVALUATED_OK));
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.storePrompt(any(PromptSpec.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PromptSpec result = service.persistEvaluatedPrompt(evaluated);

        assertThat(result.getId()).isEqualTo(PROMPT_ID);
        assertThat(result.getRevision()).isEqualTo(5);
        assertThat(result.getEvaluationResults()).isNotNull();
        assertThat(result.getEvaluationResults().getStatus()).isEqualTo(EvaluationStatus.EVALUATED_OK);

        verify(repository).getLatestVersion(PROMPT_ID);
        verify(repository).storePrompt(result);
        verifyNoInteractions(releasePolicy, eventPublisher, template, idGenerator);
    }

    @Test
    void releasePromptThrowsWhenNoEvaluationResults() {
        PromptSpec evaluated = basePromptSpec
                .withId(PROMPT_ID)
                .withRevision(4)
                .withEvaluationResults(EvaluationResults.notConfigured());
        PromptSpec released = evaluated
                .withVersion("1.0.0")
                .withReleaseMetadata(new ReleaseMetadata(
                        ReleaseMetadata.STATE_RELEASED,
                        ReleaseMetadata.MODE_DIRECT,
                        "1.0.0",
                        "group/name-v1.0.0",
                        "main",
                        null,
                        null,
                        false
                ));

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated)).thenReturn(released);

        PromptSpec promptSpec = service.releasePrompt(PROMPT_ID);
        assertThat(promptSpec).isEqualTo(released);

        verify(releasePolicy).validateRelease(evaluated);
        verify(repository).getLatestVersion(PROMPT_ID);
        verify(repository).requestRelease(evaluated);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(PromptReleasedEvent.class);
    }

    @Test
    void releasePromptThrowsWhenEvaluationFailed() {
        EvaluationResults failingResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 0.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(failingResults);

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));

        assertThatThrownBy(() -> service.releasePrompt(PROMPT_ID))
                .isInstanceOf(PromptReleaseException.class)
                .hasMessageContaining("failing evaluation results");

        verify(repository).getLatestVersion(PROMPT_ID);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(releasePolicy);
    }

    @Test
    void releasePromptThrowsWhenPolicyRejects() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        doThrow(new PromptReleaseException("policy blocked")).when(releasePolicy).validateRelease(evaluated);

        assertThatThrownBy(() -> service.releasePrompt(PROMPT_ID))
                .isInstanceOf(PromptReleaseException.class)
                .hasMessageContaining("policy blocked");

        verify(releasePolicy).validateRelease(evaluated);
        verify(repository).getLatestVersion(PROMPT_ID);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void releasePromptPublishesRequestedEventWhenStoreReturnsRequestedState() {
        EvaluationResults notConfigured = new EvaluationResults(List.of(), EvaluationStatus.NOT_CONFIGURED);
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(notConfigured);
        PromptSpec requested = evaluated.withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_REQUESTED,
                ReleaseMetadata.MODE_PR_TWO_PHASE,
                "1.0.0",
                "group/name-v1.0.0",
                "release/group-name-1.0.0",
                11,
                "https://github.com/promptLM/promptlm-app/pull/11",
                false
        ));

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated)).thenReturn(requested);

        PromptSpec result = service.releasePrompt(PROMPT_ID);

        assertThat(result).isEqualTo(requested);

        verify(releasePolicy).validateRelease(evaluated);
        verify(repository).getLatestVersion(PROMPT_ID);
        verify(repository).requestRelease(evaluated);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(PromptReleaseRequestedEvent.class);
    }

    @Test
    void releasePromptReturnsReleasedSpecWhenEvaluationSuccessful() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        PromptSpec released = evaluated
                .withVersion("1.0.0")
                .withReleaseMetadata(new ReleaseMetadata(
                        ReleaseMetadata.STATE_RELEASED,
                        ReleaseMetadata.MODE_DIRECT,
                        "1.0.0",
                        "group/name-v1.0.0",
                        "main",
                        null,
                        null,
                        false
                ));

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated)).thenReturn(released);

        PromptSpec result = service.releasePrompt(PROMPT_ID);

        assertThat(result).isEqualTo(released);

        verify(releasePolicy).validateRelease(evaluated);
        verify(repository).getLatestVersion(PROMPT_ID);
        verify(repository).requestRelease(evaluated);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(PromptReleasedEvent.class);
    }

    @Test
    void releasePromptStampsReleasedSemanticHashOnReleasedSpec() {
        // Issue #186: when a release transitions to `released`, the lifecycle
        // service must stamp the released spec's semantic hash onto the
        // metadata so the UI can compute "no changes since last release".
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        PromptSpec released = evaluated
                .withVersion("1.0.0")
                .withReleaseMetadata(new ReleaseMetadata(
                        ReleaseMetadata.STATE_RELEASED,
                        ReleaseMetadata.MODE_DIRECT,
                        "1.0.0",
                        "group/name-v1.0.0",
                        "main",
                        null,
                        null,
                        false
                ));

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated)).thenReturn(released);

        PromptSpec result = service.releasePrompt(PROMPT_ID);

        // The hash on the returned metadata matches the released spec's hash.
        ReleaseMetadata metadata = result.getReleaseMetadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.releasedSemanticHash()).isNotNull();
        assertThat(metadata.releasedSemanticHash()).isEqualTo(released.computeSemanticHash());
    }

    @Test
    void releasePromptPreservesExistingReleasedSemanticHash() {
        // Pre-stamped hash must not be overwritten — the metadata's hash is
        // the released-content baseline and must remain stable.
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        ReleaseMetadata preStamped = new ReleaseMetadata(
                ReleaseMetadata.STATE_RELEASED,
                ReleaseMetadata.MODE_DIRECT,
                "1.0.0",
                "group/name-v1.0.0",
                "main",
                null,
                null,
                false
        ).withReleasedSemanticHash("preexisting-hash");
        PromptSpec released = evaluated.withVersion("1.0.0").withReleaseMetadata(preStamped);

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated)).thenReturn(released);

        PromptSpec result = service.releasePrompt(PROMPT_ID);

        assertThat(result.getReleaseMetadata().releasedSemanticHash()).isEqualTo("preexisting-hash");
    }

    @Test
    void releasePromptDoesNotStampHashWhenStateIsRequested() {
        // PR-two-phase: the release is requested but not yet released. No
        // baseline exists to stamp until completion lands.
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        PromptSpec requested = evaluated
                .withVersion("1.0.0")
                .withReleaseMetadata(new ReleaseMetadata(
                        ReleaseMetadata.STATE_REQUESTED,
                        ReleaseMetadata.MODE_PR_TWO_PHASE,
                        "1.0.0",
                        "group/name-v1.0.0",
                        "release/group-name-1.0.0",
                        7,
                        "https://github.com/o/r/pull/7",
                        false
                ));

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated)).thenReturn(requested);

        PromptSpec result = service.releasePrompt(PROMPT_ID);

        assertThat(result.getReleaseMetadata().releasedSemanticHash()).isNull();
    }

    @Test
    void releasePromptRunsPreReleaseGateWhenEnabled() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        PromptSpec gated = evaluated.withResponse(null);
        PromptSpec released = gated
                .withVersion("1.0.0")
                .withReleaseMetadata(new ReleaseMetadata(
                        ReleaseMetadata.STATE_RELEASED,
                        ReleaseMetadata.MODE_DIRECT,
                        "1.0.0",
                        "group/name-v1.0.0",
                        "main",
                        null,
                        null,
                        false));

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(preReleaseExecuteGate.isEnabled()).thenReturn(true);
        when(preReleaseExecuteGate.runOrThrow(evaluated, OnInfraFailure.REJECT)).thenReturn(gated);
        when(repository.requestRelease(gated)).thenReturn(released);

        PromptSpec result = service.releasePrompt(PROMPT_ID);

        assertThat(result).isEqualTo(released);
        verify(preReleaseExecuteGate).runOrThrow(evaluated, OnInfraFailure.REJECT);
        verify(repository).requestRelease(gated);
    }

    @Test
    void releasePromptAbortsBeforeStoreOnPromptFailure() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(preReleaseExecuteGate.isEnabled()).thenReturn(true);
        when(preReleaseExecuteGate.runOrThrow(evaluated, OnInfraFailure.REJECT))
                .thenThrow(new PreReleasePromptFailure(PROMPT_ID, null, new IllegalArgumentException("bad")));

        assertThatThrownBy(() -> service.releasePrompt(PROMPT_ID))
                .isInstanceOf(PreReleasePromptFailure.class);

        verify(preReleaseExecuteGate).runOrThrow(evaluated, OnInfraFailure.REJECT);
        verify(repository).getLatestVersion(PROMPT_ID);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void releasePromptForwardsOnInfraFailureToGate() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        PromptSpec gated = evaluated.withResponse(null);
        PromptSpec released = gated
                .withVersion("1.0.0")
                .withReleaseMetadata(new ReleaseMetadata(
                        ReleaseMetadata.STATE_RELEASED,
                        ReleaseMetadata.MODE_DIRECT,
                        "1.0.0",
                        "group/name-v1.0.0",
                        "main",
                        null,
                        null,
                        false));

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(preReleaseExecuteGate.isEnabled()).thenReturn(true);
        when(preReleaseExecuteGate.runOrThrow(evaluated, OnInfraFailure.RECORD)).thenReturn(gated);
        when(repository.requestRelease(gated)).thenReturn(released);

        service.releasePrompt(PROMPT_ID, OnInfraFailure.RECORD);

        verify(preReleaseExecuteGate).runOrThrow(evaluated, OnInfraFailure.RECORD);
    }

    @Test
    void releasePromptSkipsGateWhenDisabled() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        PromptSpec released = evaluated
                .withVersion("1.0.0")
                .withReleaseMetadata(new ReleaseMetadata(
                        ReleaseMetadata.STATE_RELEASED,
                        ReleaseMetadata.MODE_DIRECT,
                        "1.0.0",
                        "group/name-v1.0.0",
                        "main",
                        null,
                        null,
                        false));

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(preReleaseExecuteGate.isEnabled()).thenReturn(false);
        when(repository.requestRelease(evaluated)).thenReturn(released);

        service.releasePrompt(PROMPT_ID);

        verify(preReleaseExecuteGate).isEnabled();
        verifyNoMoreInteractions(preReleaseExecuteGate);
        verify(repository).requestRelease(evaluated);
    }

    @Test
    void releasePromptThrowsWhenStoreResponseOmitsReleaseMetadata() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated)).thenReturn(evaluated.withVersion("1.0.0"));

        assertThatThrownBy(() -> service.releasePrompt(PROMPT_ID))
                .isInstanceOf(PromptReleaseException.class)
                .hasMessageContaining("required release metadata");

        verify(releasePolicy).validateRelease(evaluated);
        verify(repository).requestRelease(evaluated);
    }

    @Test
    void completeReleasePromptPublishesReleasedEvent() {
        PromptSpec existing = basePromptSpec.withId(PROMPT_ID);
        PromptSpec released = existing.withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_RELEASED,
                ReleaseMetadata.MODE_PR_TWO_PHASE,
                "1.0.0",
                "group/name-v1.0.0",
                "main",
                11,
                "https://github.com/promptLM/promptlm-app/pull/11",
                false
        ));
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.completeRelease(PROMPT_ID, "11")).thenReturn(released);

        PromptSpec result = service.completeReleasePrompt(PROMPT_ID, "11");

        assertThat(result).isEqualTo(released);
        verify(repository).completeRelease(PROMPT_ID, "11");
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(PromptReleasedEvent.class);
    }

    @Test
    void completeReleasePromptFailsWhenStoreDoesNotReturnReleasedState() {
        PromptSpec existing = basePromptSpec.withId(PROMPT_ID);
        PromptSpec requested = existing.withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_REQUESTED,
                ReleaseMetadata.MODE_PR_TWO_PHASE,
                "1.0.0",
                "group/name-v1.0.0",
                "release/group-name-1.0.0",
                11,
                "https://github.com/promptLM/promptlm-app/pull/11",
                false
        ));
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.completeRelease(PROMPT_ID, "11")).thenReturn(requested);

        assertThatThrownBy(() -> service.completeReleasePrompt(PROMPT_ID, "11"))
                .isInstanceOf(PromptReleaseException.class)
                .hasMessageContaining("did not return state 'released'");
    }

    // -----------------------------------------------------------------------
    // Audit-log smoke tests (issue #126)
    //
    // The pre-release-execute gate (#96) is a Mockito mock; isEnabled() returns
    // false by default, so these tests bypass the gate. The dedicated gate-related
    // audit tests below stub the gate explicitly to drive the BLOCKED_INFRA /
    // executionId paths that the gate introduces.
    // -----------------------------------------------------------------------

    private ReleaseAuditEvent captureSingleAuditEvent() {
        ArgumentCaptor<ReleaseAuditEvent> captor = ArgumentCaptor.forClass(ReleaseAuditEvent.class);
        verify(auditLogger).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void releasePromptEmitsReleasedAuditOnDirectReleaseSuccess() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        PromptSpec released = evaluated.withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_RELEASED,
                ReleaseMetadata.MODE_DIRECT,
                "1.0.0",
                "group/name-v1.0.0",
                "main",
                null,
                null,
                false));
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated)).thenReturn(released);

        service.releasePrompt(PROMPT_ID);

        ReleaseAuditEvent event = captureSingleAuditEvent();
        assertThat(event.outcome()).isEqualTo(ReleaseAuditOutcome.RELEASED);
        assertThat(event.promptSpecId()).isEqualTo(PROMPT_ID);
        assertThat(event.mode()).isEqualTo(ReleaseMetadata.MODE_DIRECT);
        assertThat(event.pullRequestReference()).isNull();
        assertThat(event.correlationId()).isNotBlank();
        assertThat(event.caller()).isNull();
        assertThat(event.onInfraFailure()).isEqualTo("REJECT");
        assertThat(event.executionId()).isNull();
        assertThat(event.exceptionType()).isNull();
        assertThat(event.exceptionMessage()).isNull();
    }

    @Test
    void releasePromptEmitsRequestedAuditOnPrTwoPhaseRequestedState() {
        EvaluationResults notConfigured = new EvaluationResults(List.of(), EvaluationStatus.NOT_CONFIGURED);
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(notConfigured);
        PromptSpec requested = evaluated.withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_REQUESTED,
                ReleaseMetadata.MODE_PR_TWO_PHASE,
                "1.0.0",
                "group/name-v1.0.0",
                "release/group-name-1.0.0",
                11,
                "https://github.com/promptLM/promptlm-app/pull/11",
                false));
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated)).thenReturn(requested);

        service.releasePrompt(PROMPT_ID);

        ReleaseAuditEvent event = captureSingleAuditEvent();
        assertThat(event.outcome()).isEqualTo(ReleaseAuditOutcome.REQUESTED);
        assertThat(event.mode()).isEqualTo(ReleaseMetadata.MODE_PR_TWO_PHASE);
        assertThat(event.exceptionType()).isNull();
    }

    @Test
    void releasePromptEmitsBlockedPromptAuditOnFailingEvaluation() {
        EvaluationResults failingResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 0.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(failingResults);
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));

        assertThatThrownBy(() -> service.releasePrompt(PROMPT_ID))
                .isInstanceOf(PromptReleaseException.class);

        ReleaseAuditEvent event = captureSingleAuditEvent();
        assertThat(event.outcome()).isEqualTo(ReleaseAuditOutcome.BLOCKED_PROMPT);
        assertThat(event.promptSpecId()).isEqualTo(PROMPT_ID);
        assertThat(event.mode()).isNull();
        assertThat(event.correlationId()).isNotBlank();
        assertThat(event.exceptionType()).isEqualTo("PromptReleaseException");
        assertThat(event.exceptionMessage()).contains("failing evaluation results");
    }

    @Test
    void releasePromptEmitsBlockedPromptAuditOnPolicyReject() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        doThrow(new PromptReleaseException("policy blocked")).when(releasePolicy).validateRelease(evaluated);

        assertThatThrownBy(() -> service.releasePrompt(PROMPT_ID))
                .isInstanceOf(PromptReleaseException.class);

        ReleaseAuditEvent event = captureSingleAuditEvent();
        assertThat(event.outcome()).isEqualTo(ReleaseAuditOutcome.BLOCKED_PROMPT);
        assertThat(event.exceptionType()).isEqualTo("PromptReleaseException");
        assertThat(event.exceptionMessage()).isEqualTo("policy blocked");
    }

    @Test
    void releasePromptEmitsBlockedInfraAuditWhenStoreThrowsRuntime() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated))
                .thenThrow(new RuntimeException("simulated infra failure"));

        assertThatThrownBy(() -> service.releasePrompt(PROMPT_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated infra failure");

        ReleaseAuditEvent event = captureSingleAuditEvent();
        assertThat(event.outcome()).isEqualTo(ReleaseAuditOutcome.BLOCKED_INFRA);
        assertThat(event.exceptionType()).isEqualTo("RuntimeException");
        assertThat(event.exceptionMessage()).isEqualTo("simulated infra failure");
    }

    @Test
    void releasePromptEmitsBlockedInfraAuditWithExecutionIdOnPreReleaseInfrastructureFailure() {
        // Gate-driven sad path: PreReleaseInfrastructureFailure must classify as BLOCKED_INFRA
        // even though it extends PromptReleaseException, and executionId must come from the
        // failed Execution attached to the exception.
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(preReleaseExecuteGate.isEnabled()).thenReturn(true);
        dev.promptlm.domain.promptspec.Execution failedInfra = new dev.promptlm.domain.promptspec.Execution();
        failedInfra.setId("exec-infra-1");
        when(preReleaseExecuteGate.runOrThrow(evaluated, OnInfraFailure.REJECT))
                .thenThrow(new PreReleaseInfrastructureFailure(PROMPT_ID, failedInfra, new RuntimeException("vendor 503")));

        assertThatThrownBy(() -> service.releasePrompt(PROMPT_ID))
                .isInstanceOf(PreReleaseInfrastructureFailure.class);

        ReleaseAuditEvent event = captureSingleAuditEvent();
        assertThat(event.outcome()).isEqualTo(ReleaseAuditOutcome.BLOCKED_INFRA);
        assertThat(event.executionId()).isEqualTo("exec-infra-1");
        assertThat(event.onInfraFailure()).isEqualTo("REJECT");
        assertThat(event.exceptionType()).isEqualTo("PreReleaseInfrastructureFailure");
    }

    @Test
    void releasePromptEmitsBlockedPromptAuditWithExecutionIdOnPreReleasePromptFailure() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(preReleaseExecuteGate.isEnabled()).thenReturn(true);
        dev.promptlm.domain.promptspec.Execution failedPrompt = new dev.promptlm.domain.promptspec.Execution();
        failedPrompt.setId("exec-prompt-1");
        when(preReleaseExecuteGate.runOrThrow(evaluated, OnInfraFailure.REJECT))
                .thenThrow(new PreReleasePromptFailure(PROMPT_ID, failedPrompt, new IllegalArgumentException("schema")));

        assertThatThrownBy(() -> service.releasePrompt(PROMPT_ID))
                .isInstanceOf(PreReleasePromptFailure.class);

        ReleaseAuditEvent event = captureSingleAuditEvent();
        assertThat(event.outcome()).isEqualTo(ReleaseAuditOutcome.BLOCKED_PROMPT);
        assertThat(event.executionId()).isEqualTo("exec-prompt-1");
        assertThat(event.exceptionType()).isEqualTo("PreReleasePromptFailure");
    }

    @Test
    void releasePromptForwardsOnInfraFailureRecordIntoAuditEvent() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        PromptSpec released = evaluated.withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_RELEASED,
                ReleaseMetadata.MODE_DIRECT,
                "1.0.0",
                "group/name-v1.0.0",
                "main",
                null,
                null,
                false));
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated)).thenReturn(released);

        service.releasePrompt(PROMPT_ID, OnInfraFailure.RECORD);

        ReleaseAuditEvent event = captureSingleAuditEvent();
        assertThat(event.onInfraFailure()).isEqualTo("RECORD");
    }

    @Test
    void completeReleasePromptEmitsReleasedAudit() {
        PromptSpec existing = basePromptSpec.withId(PROMPT_ID);
        PromptSpec released = existing.withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_RELEASED,
                ReleaseMetadata.MODE_PR_TWO_PHASE,
                "1.0.0",
                "group/name-v1.0.0",
                "main",
                11,
                "https://github.com/promptLM/promptlm-app/pull/11",
                false));
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.completeRelease(PROMPT_ID, "11")).thenReturn(released);

        service.completeReleasePrompt(PROMPT_ID, "11");

        ReleaseAuditEvent event = captureSingleAuditEvent();
        assertThat(event.outcome()).isEqualTo(ReleaseAuditOutcome.RELEASED);
        assertThat(event.promptSpecId()).isEqualTo(PROMPT_ID);
        assertThat(event.mode()).isEqualTo(ReleaseMetadata.MODE_PR_TWO_PHASE);
        assertThat(event.pullRequestReference()).isEqualTo("11");
        assertThat(event.correlationId()).isNotBlank();
        assertThat(event.exceptionType()).isNull();
    }

    @Test
    void completeReleasePromptEmitsBlockedPromptAuditOnNotReleasedState() {
        PromptSpec existing = basePromptSpec.withId(PROMPT_ID);
        PromptSpec requested = existing.withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_REQUESTED,
                ReleaseMetadata.MODE_PR_TWO_PHASE,
                "1.0.0",
                "group/name-v1.0.0",
                "release/group-name-1.0.0",
                11,
                "https://github.com/promptLM/promptlm-app/pull/11",
                false));
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.completeRelease(PROMPT_ID, "11")).thenReturn(requested);

        assertThatThrownBy(() -> service.completeReleasePrompt(PROMPT_ID, "11"))
                .isInstanceOf(PromptReleaseException.class);

        ReleaseAuditEvent event = captureSingleAuditEvent();
        assertThat(event.outcome()).isEqualTo(ReleaseAuditOutcome.BLOCKED_PROMPT);
        assertThat(event.pullRequestReference()).isEqualTo("11");
        assertThat(event.exceptionType()).isEqualTo("PromptReleaseException");
    }

    @Test
    void releasePromptStillSucceedsWhenAuditLoggerThrowsOnSuccessPath() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        PromptSpec released = evaluated.withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_RELEASED,
                ReleaseMetadata.MODE_DIRECT,
                "1.0.0",
                "group/name-v1.0.0",
                "main",
                null,
                null,
                false));
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated)).thenReturn(released);
        doThrow(new RuntimeException("audit boom")).when(auditLogger).record(any(ReleaseAuditEvent.class));

        PromptSpec result = service.releasePrompt(PROMPT_ID);

        assertThat(result).isEqualTo(released);
    }

    @Test
    void releasePromptPreservesOriginalExceptionWhenAuditLoggerThrowsOnBlockedPromptPath() {
        EvaluationResults failingResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 0.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(failingResults);
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        doThrow(new RuntimeException("audit boom")).when(auditLogger).record(any(ReleaseAuditEvent.class));

        assertThatThrownBy(() -> service.releasePrompt(PROMPT_ID))
                .isInstanceOf(PromptReleaseException.class)
                .hasMessageContaining("failing evaluation results");
    }

    @Test
    void releasePromptPreservesOriginalExceptionWhenAuditLoggerThrowsOnBlockedInfraPath() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated))
                .thenThrow(new RuntimeException("simulated infra failure"));
        doThrow(new RuntimeException("audit boom")).when(auditLogger).record(any(ReleaseAuditEvent.class));

        assertThatThrownBy(() -> service.releasePrompt(PROMPT_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated infra failure");
    }

    @Test
    void completeReleasePromptStillSucceedsWhenAuditLoggerThrowsOnSuccessPath() {
        PromptSpec existing = basePromptSpec.withId(PROMPT_ID);
        PromptSpec released = existing.withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_RELEASED,
                ReleaseMetadata.MODE_PR_TWO_PHASE,
                "1.0.0",
                "group/name-v1.0.0",
                "main",
                11,
                "https://github.com/promptLM/promptlm-app/pull/11",
                false));
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.completeRelease(PROMPT_ID, "11")).thenReturn(released);
        doThrow(new RuntimeException("audit boom")).when(auditLogger).record(any(ReleaseAuditEvent.class));

        PromptSpec result = service.completeReleasePrompt(PROMPT_ID, "11");

        assertThat(result).isEqualTo(released);
    }

    @Test
    void releasePromptStillSucceedsWhenAuditContextThrows() {
        ReleaseAuditContext throwing = new ReleaseAuditContext() {
            @Override public String caller() { throw new RuntimeException("caller boom"); }
            @Override public String correlationId() { throw new RuntimeException("corr boom"); }
            @Override public String executionIdFor(String id) { throw new RuntimeException("exec boom"); }
        };
        DefaultPromptLifecycleService throwingCtxService = new DefaultPromptLifecycleService(
                repository, template, idGenerator, eventPublisher, releasePolicy,
                preReleaseExecuteGate, auditLogger, throwing);

        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        PromptSpec released = evaluated.withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_RELEASED,
                ReleaseMetadata.MODE_DIRECT,
                "1.0.0",
                "group/name-v1.0.0",
                "main",
                null,
                null,
                false));
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated)).thenReturn(released);

        PromptSpec result = throwingCtxService.releasePrompt(PROMPT_ID);

        assertThat(result).isEqualTo(released);
        ReleaseAuditEvent event = captureSingleAuditEvent();
        assertThat(event.outcome()).isEqualTo(ReleaseAuditOutcome.RELEASED);
        assertThat(event.correlationId()).isNotBlank();
        assertThat(event.caller()).isNull();
        assertThat(event.executionId()).isNull();
    }

    @Test
    void releasePromptEmitsExactlyOneAuditEntryPerCall() {
        EvaluationResults successResults = new EvaluationResults(List.of(new EvaluationResult("eval", "type", 1.0, null, null)));
        PromptSpec evaluated = basePromptSpec.withEvaluationResults(successResults);
        PromptSpec released = evaluated.withReleaseMetadata(new ReleaseMetadata(
                ReleaseMetadata.STATE_RELEASED,
                ReleaseMetadata.MODE_DIRECT,
                "1.0.0",
                "group/name-v1.0.0",
                "main",
                null,
                null,
                false));
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(evaluated));
        when(repository.requestRelease(evaluated)).thenReturn(released);

        service.releasePrompt(PROMPT_ID);

        verify(auditLogger).record(any(ReleaseAuditEvent.class));
        verifyNoMoreInteractions(auditLogger);
    }

    @Test
    void updatePromptClearsExecutionsWhenRevisionBumps() {
        Execution prior = new Execution(
                "exec-prior",
                Instant.parse("2026-05-12T10:00:00Z"),
                new ChatCompletionResponse(10L, null, "old"),
                null, null);
        PromptSpec existing = basePromptSpec.withRevision(3).withExecutions(List.of(prior));
        PromptSpec updating = existing.withRequest(ChatCompletionRequest.builder()
                .withVendor("openai")
                .withModel("gpt-4")
                .withMessages(List.of(ChatCompletionRequest.Message.builder()
                        .withRole("user")
                        .withContent("revised")
                        .build()))
                .build());

        assertThat(updating.hasSemanticChangesComparedTo(existing)).isTrue();

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.commitLocally(any(PromptSpec.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PromptSpec result = service.updatePrompt(PROMPT_ID, updating);

        assertThat(result.getRevision()).isEqualTo(4);
        assertThat(result.getExecutions()).isEmpty();
    }

    @Test
    void updatePromptKeepsExecutionsWhenOnlyMetadataChanged() {
        Execution prior = new Execution(
                "exec-prior",
                Instant.parse("2026-05-12T10:00:00Z"),
                new ChatCompletionResponse(10L, null, "kept"),
                null, null);
        PromptSpec existing = basePromptSpec.withRevision(3).withExecutions(List.of(prior));
        PromptSpec updating = existing.withDescription("new-desc");

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.commitLocally(any(PromptSpec.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PromptSpec result = service.updatePrompt(PROMPT_ID, updating);

        assertThat(result.getRevision()).isEqualTo(3);
        assertThat(result.getExecutions()).hasSize(1);
        assertThat(result.getExecutions().get(0).getId()).isEqualTo("exec-prior");
    }

    @Test
    void recordExecutionAppendsAndPersists() {
        PromptSpec existing = basePromptSpec.withRevision(3);
        Execution toRecord = new Execution(
                "exec-new",
                Instant.parse("2026-05-12T12:00:00Z"),
                new ChatCompletionResponse(10L, null, "fresh"),
                null, null);

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.storePrompt(any(PromptSpec.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PromptSpec result = service.recordExecution(PROMPT_ID, toRecord);

        assertThat(result.getRevision()).isEqualTo(3);
        assertThat(result.getExecutions()).hasSize(1);
        assertThat(result.getExecutions().get(0).getId()).isEqualTo("exec-new");
        assertThat(((ChatCompletionResponse) result.getResponse()).getContent()).isEqualTo("fresh");

        verify(repository).storePrompt(result);
    }

    @Test
    void recordExecutionCapsListAtMaximumDropsOldest() {
        List<Execution> sixExisting = IntStream.range(0, 6)
                .mapToObj(i -> new Execution(
                        "exec-" + i,
                        Instant.parse("2026-05-12T10:00:00Z").plusSeconds(i),
                        new ChatCompletionResponse(10L, null, "n-" + i),
                        null, null))
                .toList();
        PromptSpec existing = basePromptSpec.withRevision(3).withExecutions(sixExisting);

        Execution incoming = new Execution(
                "exec-newest",
                Instant.parse("2026-05-12T13:00:00Z"),
                new ChatCompletionResponse(10L, null, "newest"),
                null, null);

        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.of(existing));
        when(repository.storePrompt(any(PromptSpec.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PromptSpec result = service.recordExecution(PROMPT_ID, incoming);

        // Cap is 5; six existing + new = 7 → drop two oldest, keep last 5.
        assertThat(result.getExecutions()).hasSize(PromptLifecycleService.MAX_DEV_EXECUTIONS);
        assertThat(result.getExecutions())
                .extracting(Execution::getId)
                .containsExactly("exec-2", "exec-3", "exec-4", "exec-5", "exec-newest");
    }

    @Test
    void recordExecutionRejectsNull() {
        assertThatThrownBy(() -> service.recordExecution(PROMPT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordExecutionThrowsWhenPromptMissing() {
        Execution exec = new Execution(
                "exec-x", Instant.now(), null, null, null);
        when(repository.getLatestVersion(PROMPT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.recordExecution(PROMPT_ID, exec))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
