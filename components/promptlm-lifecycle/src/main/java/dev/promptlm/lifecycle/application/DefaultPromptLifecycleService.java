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

import dev.promptlm.domain.events.PromptCreatedEvent;
import dev.promptlm.domain.events.PromptReleaseRequestedEvent;
import dev.promptlm.domain.events.PromptReleasedEvent;
import dev.promptlm.domain.events.PromptUpdatedEvent;
import dev.promptlm.domain.promptspec.ChatCompletionRequest;
import dev.promptlm.domain.promptspec.EvaluationStatus;
import dev.promptlm.domain.promptspec.EvaluationResults;
import dev.promptlm.domain.promptspec.Execution;
import dev.promptlm.domain.promptspec.PromptSpec;
import dev.promptlm.domain.promptspec.ReleaseMetadata;
import dev.promptlm.domain.promptspec.Request;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
class DefaultPromptLifecycleService implements PromptLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPromptLifecycleService.class);
    private final PromptStorePort repository;
    private final PromptTemplatePort template;
    private final PromptIdGeneratorPort idGenerator;
    private final PromptLifecycleEventPublisher eventPublisher;
    private final PromptReleasePolicy releasePolicy;
    private final PreReleaseExecuteGate preReleaseExecuteGate;
    private final ReleaseAuditLogger auditLogger;
    private final ReleaseAuditContext auditContext;

    DefaultPromptLifecycleService(PromptStorePort repository,
                                  PromptTemplatePort template,
                                  PromptIdGeneratorPort idGenerator,
                                  PromptLifecycleEventPublisher eventPublisher,
                                  PromptReleasePolicy releasePolicy,
                                  PreReleaseExecuteGate preReleaseExecuteGate,
                                  ReleaseAuditLogger auditLogger,
                                  ReleaseAuditContext auditContext) {
        this.repository = repository;
        this.template = template;
        this.idGenerator = idGenerator;
        this.eventPublisher = eventPublisher;
        this.releasePolicy = releasePolicy;
        this.preReleaseExecuteGate = preReleaseExecuteGate;
        this.auditLogger = auditLogger;
        this.auditContext = auditContext;
    }

    @Override
    public PromptSpec createPrompt(String group,
                                   Map<String, String> placeholder,
                                   String name,
                                   List<ChatCompletionRequest.Message> userMessage,
                                   PromptSpec.Placeholders placeholders,
                                   Map<String, JsonNode> extensions) {
        String normalizedGroup = normalizeKey(group);
        String normalizedName = normalizeKey(name);

        repository.findPromptSpec(normalizedGroup, normalizedName)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Prompt %s already exists".formatted(normalizedGroup + "/" + normalizedName));
                });

        String templateContent = repository.findPromptSpecTemplate(normalizedGroup);
        PromptSpec.Placeholders placeholderConfig = placeholders != null ? placeholders : new PromptSpec.Placeholders();
        if (placeholderConfig.getStartPattern() == null) {
            placeholderConfig.setStartPattern("{{");
        }
        if (placeholderConfig.getEndPattern() == null) {
            placeholderConfig.setEndPattern("}}");
        }

        String id = idGenerator.generateId(normalizedGroup, normalizedName, userMessage);
        PromptSpec promptSpec = template.render(templateContent, normalizedGroup, normalizedName, userMessage, placeholder, placeholderConfig)
                .withId(id);
        if (extensions != null && !extensions.isEmpty()) {
            promptSpec = promptSpec.withExtensions(mergeExtensions(promptSpec.getExtensions(), extensions));
        }
        // #352: save commits locally only. The post-execution push listener handles the
        // single remote write once the LLM response has been attached.
        PromptSpec stored = repository.commitLocally(promptSpec);
        eventPublisher.publishEvent(new PromptCreatedEvent(stored));
        return stored;
    }

    @Override
    public PromptSpec createPromptSpec(PromptSpec promptSpec) throws PromptSpecAlreadyExistsException {
        if (promptSpec == null) {
            throw new IllegalArgumentException("Prompt spec must not be null");
        }

        String normalizedGroup = normalizeKey(promptSpec.getGroup());
        String normalizedName = normalizeKey(promptSpec.getName());
        PromptSpec normalizedSpec = promptSpec
                .withGroup(normalizedGroup)
                .withName(normalizedName);

        repository.findPromptSpec(normalizedGroup, normalizedName)
                .ifPresent(existing -> {
                    throw new PromptSpecAlreadyExistsException(promptSpec.getGroup(), promptSpec.getName());
                });


        PromptSpec withId = normalizedSpec.getId() != null
                ? normalizedSpec
                : normalizedSpec.withId(idGenerator.generateId(
                        normalizedGroup,
                        normalizedName,
                        extractMessages(normalizedSpec)
                ));

        // #352: save commits locally only. The post-execution push listener performs
        // the single remote push once the LLM response has been attached.
        PromptSpec stored = repository.commitLocally(withId);
        eventPublisher.publishEvent(new PromptCreatedEvent(stored));
        return stored;
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return null;
        }

        return value.toLowerCase(Locale.ROOT);
    }

    @Override
    public PromptSpec createDefaultPromptSpec() {
        // Issue #309 — the New-prompt editor must open blank. Previously this method
        // returned a demo "support-prompt" seed (name/group/description/messages/
        // placeholders all pre-filled), which both (a) pre-populated the form in the
        // web UI and (b) made it trivially easy for users to save the demo content
        // into their own repo (e.g. as prompts/support/support-prompt/promptlm.yml)
        // by accepting the defaults. We now return an empty draft with only the
        // structural defaults (placeholder delimiters, sane parameter defaults,
        // a `chat/completion` request shape with empty system/user message slots)
        // so the editor presents truly blank fields.
        Map<String, Object> defaultParams = new HashMap<>();
        defaultParams.put("temperature", 0.7);
        defaultParams.put("maxTokens", 1024);
        defaultParams.put("topP", 1.0);
        defaultParams.put("frequencyPenalty", 0.0);
        defaultParams.put("presencePenalty", 0.0);
        defaultParams.put("stream", false);

        PromptSpec.Placeholders placeholders = new PromptSpec.Placeholders();
        placeholders.setStartPattern("{{");
        placeholders.setEndPattern("}}");
        placeholders.setList(List.of());

        Request request = ChatCompletionRequest.builder()
                .withVendor("")
                .withModel("")
                .withMessages(List.of(
                        ChatCompletionRequest.Message.builder()
                                .withRole("system")
                                .withContent("")
                                .build(),
                        ChatCompletionRequest.Message.builder()
                                .withRole("user")
                                .withContent("")
                                .build()
                ))
                .withParameters(defaultParams)
                .build();

        return PromptSpec.builder()
                .withGroup("")
                .withName("")
                .withVersion("1.0.0-SNAPSHOT")
                .withRevision(1)
                .withDescription("")
                .withRequest(request)
                .withPlaceholders(placeholders)
                .build();
    }

    @Override
    public PromptSpec updatePrompt(String promptSpecId, PromptSpec updatingSpec) {
        PromptSpec promptSpec = repository.getLatestVersion(promptSpecId)
                .orElseThrow(() -> new IllegalArgumentException("Prompt %s does not exist"));

        PromptSpec updated = promptSpec
                .withDescription(updatingSpec.getDescription())
                .withRequest(updatingSpec.getRequest())
                .withPlaceholders(updatingSpec.getPlaceholders())
                .withAuthors(updatingSpec.getAuthors())
                .withPurpose(updatingSpec.getPurpose())
                .withRepositoryUrl(updatingSpec.getRepositoryUrl())
                .withExtensions(mergeExtensions(promptSpec.getExtensions(), updatingSpec.getExtensions()));
        if (updatingSpec.getResponse() != null) {
            updated = updated.withResponse(updatingSpec.getResponse());
        }

        boolean semanticChanged = updated.hasSemanticChangesComparedTo(promptSpec);
        // A semantic change (request shape, placeholders, ...) invalidates prior responses;
        // clear executions[] in the same branch that bumps revision so the invariant
        // holds across all update paths (editor save, API PUT, YAML reload). See #140.
        PromptSpec versioned = semanticChanged
                ? increaseRevision(updated).withExecutions(List.of())
                : updated;
        PromptSpec hashed = versioned.withSemanticHashComputed();
        // #352: save commits locally only. The push happens after async execution.
        repository.commitLocally(hashed);
        eventPublisher.publishEvent(new PromptUpdatedEvent(hashed));
        return hashed;
    }

    @Override
    public PromptSpec recordExecution(String promptSpecId, Execution execution) {
        if (execution == null) {
            throw new IllegalArgumentException("execution must not be null");
        }
        PromptSpec stored = repository.getLatestVersion(promptSpecId)
                .orElseThrow(() -> new IllegalArgumentException("Prompt " + promptSpecId + " does not exist"));

        List<Execution> next = appendCapped(stored.getExecutions(), execution, MAX_DEV_EXECUTIONS);
        PromptSpec updated = stored.withExecutions(next);
        if (execution.getResponse() != null) {
            updated = updated.withResponse(execution.getResponse());
        }
        // Recompute the semantic hash defensively; executions and response don't
        // contribute to it but other callers may have left a stale hash.
        PromptSpec hashed = updated.withSemanticHashComputed();
        repository.storePrompt(hashed);
        return hashed;
    }

    private static List<Execution> appendCapped(List<Execution> existing, Execution toAdd, int cap) {
        List<Execution> merged = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        merged.add(toAdd);
        while (merged.size() > cap) {
            merged.remove(0);
        }
        return merged;
    }

    @Override
    public PromptSpec releasePrompt(String promptSpecId) {
        return releasePrompt(promptSpecId, OnInfraFailure.REJECT);
    }

    @Override
    public PromptSpec releasePrompt(String promptSpecId, OnInfraFailure onInfraFailure) {
        AuditContextSnapshot snapshot = readAuditContext(promptSpecId);
        try {
            PromptSpec released = doReleasePrompt(promptSpecId, onInfraFailure);
            ReleaseMetadata metadata = released.getReleaseMetadata();
            String mode = metadata == null ? null : metadata.mode();
            ReleaseAuditOutcome outcome = metadata != null && metadata.isReleased()
                    ? ReleaseAuditOutcome.RELEASED
                    : ReleaseAuditOutcome.REQUESTED;
            safelyRecord(ReleaseAuditEvent.forSuccess(
                    outcome,
                    promptSpecId,
                    mode,
                    null,
                    snapshot.correlationId,
                    snapshot.caller,
                    snapshot.executionId,
                    nameOf(onInfraFailure)));
            return released;
            // PreReleaseInfrastructureFailure must be classified BLOCKED_INFRA even though
            // it extends PromptReleaseException — the pre-release-execute gate (#96) maps
            // vendor 5xx / network timeouts / outages to this type. The exception also
            // carries the failed Execution, so executionId is real here, not a sentinel.
        } catch (PreReleaseInfrastructureFailure e) {
            safelyRecord(ReleaseAuditEvent.forFailure(
                    ReleaseAuditOutcome.BLOCKED_INFRA,
                    promptSpecId,
                    null,
                    null,
                    snapshot.correlationId,
                    snapshot.caller,
                    executionIdOf(e.failedExecution(), snapshot.executionId),
                    nameOf(onInfraFailure),
                    e));
            throw e;
        } catch (PreReleasePromptFailure e) {
            safelyRecord(ReleaseAuditEvent.forFailure(
                    ReleaseAuditOutcome.BLOCKED_PROMPT,
                    promptSpecId,
                    null,
                    null,
                    snapshot.correlationId,
                    snapshot.caller,
                    executionIdOf(e.failedExecution(), snapshot.executionId),
                    nameOf(onInfraFailure),
                    e));
            throw e;
            // IllegalArgumentException is included defensively: nothing in doReleasePrompt
            // currently throws it, but the wrap should still classify any future domain
            // assertion as BLOCKED_PROMPT rather than miscategorising it as infra.
        } catch (PromptReleaseException | IllegalStateException | IllegalArgumentException e) {
            safelyRecord(ReleaseAuditEvent.forFailure(
                    ReleaseAuditOutcome.BLOCKED_PROMPT,
                    promptSpecId,
                    null,
                    null,
                    snapshot.correlationId,
                    snapshot.caller,
                    snapshot.executionId,
                    nameOf(onInfraFailure),
                    e));
            throw e;
        } catch (RuntimeException e) {
            safelyRecord(ReleaseAuditEvent.forFailure(
                    ReleaseAuditOutcome.BLOCKED_INFRA,
                    promptSpecId,
                    null,
                    null,
                    snapshot.correlationId,
                    snapshot.caller,
                    snapshot.executionId,
                    nameOf(onInfraFailure),
                    e));
            throw e;
        }
    }

    private PromptSpec doReleasePrompt(String promptSpecId, OnInfraFailure onInfraFailure) {
        PromptSpec promptSpec = repository.getLatestVersion(promptSpecId)
                .orElseThrow(() -> new PromptReleaseException(
                        "Could not find prompt %s".formatted(promptSpecId)));

        EvaluationResults results = promptSpec.getEvaluationResults();

        if(results == null) {
            throw new IllegalStateException("Prompt %s has null evaluation".formatted(promptSpecId));
        }

        if (results.getStatus() != EvaluationStatus.NOT_CONFIGURED && !results.success()) {
            throw new PromptReleaseException(
                    "Prompt %s has failing evaluation results and was not released".formatted(promptSpecId));
        }

        if (results.getStatus() == EvaluationStatus.NOT_CONFIGURED) {
            log.info("Prompt %s no evals configured, proceeding release.".formatted(promptSpecId));
        }

        releasePolicy.validateRelease(promptSpec);

        PromptSpec gated = preReleaseExecuteGate.isEnabled()
                ? preReleaseExecuteGate.runOrThrow(promptSpec, onInfraFailure)
                : promptSpec;

        PromptSpec released = repository.requestRelease(gated);
        ReleaseMetadata releaseMetadata = requireReleaseMetadata(released);
        if (releaseMetadata.isRequested()) {
            eventPublisher.publishEvent(new PromptReleaseRequestedEvent(released));
        } else if (releaseMetadata.isReleased()) {
            released = stampReleasedSemanticHash(released);
            eventPublisher.publishEvent(new PromptReleasedEvent(released));
        } else {
            throw new PromptReleaseException("Unsupported release state '%s' for prompt %s"
                    .formatted(releaseMetadata.state(), promptSpecId));
        }

        return released;
    }

    @Override
    public PromptSpec completeReleasePrompt(String promptSpecId, String pullRequestReference) {
        AuditContextSnapshot snapshot = readAuditContext(promptSpecId);
        try {
            PromptSpec released = doCompleteReleasePrompt(promptSpecId, pullRequestReference);
            ReleaseMetadata metadata = released.getReleaseMetadata();
            String mode = metadata == null ? null : metadata.mode();
            safelyRecord(ReleaseAuditEvent.forSuccess(
                    ReleaseAuditOutcome.RELEASED,
                    promptSpecId,
                    mode,
                    pullRequestReference,
                    snapshot.correlationId,
                    snapshot.caller,
                    snapshot.executionId,
                    null));
            return released;
        } catch (PromptReleaseException | IllegalStateException | IllegalArgumentException e) {
            safelyRecord(ReleaseAuditEvent.forFailure(
                    ReleaseAuditOutcome.BLOCKED_PROMPT,
                    promptSpecId,
                    null,
                    pullRequestReference,
                    snapshot.correlationId,
                    snapshot.caller,
                    snapshot.executionId,
                    null,
                    e));
            throw e;
        } catch (RuntimeException e) {
            safelyRecord(ReleaseAuditEvent.forFailure(
                    ReleaseAuditOutcome.BLOCKED_INFRA,
                    promptSpecId,
                    null,
                    pullRequestReference,
                    snapshot.correlationId,
                    snapshot.caller,
                    snapshot.executionId,
                    null,
                    e));
            throw e;
        }
    }

    /**
     * Emit an audit event without letting any failure escape. Belt-and-suspenders to the
     * no-throw contract on {@link ReleaseAuditLogger#record}: a third-party implementation
     * that violates the contract must not be able to (a) cause a successful release to
     * surface as a caller-visible exception or (b) replace the original release-path
     * exception inside a catch block. Errors (OOM, StackOverflowError) propagate.
     */
    private void safelyRecord(ReleaseAuditEvent event) {
        try {
            auditLogger.record(event);
        } catch (RuntimeException auditEx) {
            log.warn("Release audit emission failed; release behaviour preserved.", auditEx);
        }
    }

    /**
     * Read the audit context defensively. The {@link ReleaseAuditContext} javadoc forbids
     * implementations from throwing, but a misbehaving custom implementation must not be able
     * to alter release behaviour. Any exception here is downgraded to the safe default
     * (caller=null, executionId=null, fresh UUID for correlationId) and the operational
     * logger records the failure once so it isn't silently lost.
     */
    private AuditContextSnapshot readAuditContext(String promptSpecId) {
        String correlationId;
        String caller;
        String executionId;
        try {
            correlationId = auditContext.correlationId();
        } catch (RuntimeException e) {
            log.warn("ReleaseAuditContext.correlationId() failed; using fallback UUID.", e);
            correlationId = java.util.UUID.randomUUID().toString();
        }
        try {
            caller = auditContext.caller();
        } catch (RuntimeException e) {
            log.warn("ReleaseAuditContext.caller() failed; using null.", e);
            caller = null;
        }
        try {
            executionId = auditContext.executionIdFor(promptSpecId);
        } catch (RuntimeException e) {
            log.warn("ReleaseAuditContext.executionIdFor() failed; using null.", e);
            executionId = null;
        }
        return new AuditContextSnapshot(correlationId, caller, executionId);
    }

    private record AuditContextSnapshot(String correlationId, String caller, String executionId) {
    }

    private static String nameOf(OnInfraFailure value) {
        return value == null ? null : value.name();
    }

    /** Prefer the gating Execution's id when the gate (or its failure) attached one. */
    private static String executionIdOf(Execution execution, String fallback) {
        if (execution == null) {
            return fallback;
        }
        try {
            String id = execution.getId();
            return id == null || id.isBlank() ? fallback : id;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private PromptSpec doCompleteReleasePrompt(String promptSpecId, String pullRequestReference) {
        repository.getLatestVersion(promptSpecId)
                .orElseThrow(() -> new PromptReleaseException(
                        "Could not find prompt %s".formatted(promptSpecId)));

        PromptSpec released = repository.completeRelease(promptSpecId, pullRequestReference);
        ReleaseMetadata releaseMetadata = requireReleaseMetadata(released);
        if (!releaseMetadata.isReleased()) {
            throw new PromptReleaseException("Release completion for prompt %s did not return state 'released'"
                    .formatted(promptSpecId));
        }

        released = stampReleasedSemanticHash(released);
        eventPublisher.publishEvent(new PromptReleasedEvent(released));
        return released;
    }

    /**
     * Stamps the prompt's current semantic hash onto the existing
     * {@link ReleaseMetadata} so the UI can compare future revisions against
     * the released baseline (see issue #186). The store-level
     * {@code requestRelease} / {@code completeRelease} implementations don't
     * know about semantic hashing, so we stamp it here at the lifecycle
     * boundary where {@link PromptSpec#computeSemanticHash()} is available.
     *
     * <p>No-op when there is no release metadata, the hash cannot be computed,
     * or the metadata already carries a {@code releasedSemanticHash} — we
     * never overwrite an existing baseline because release re-stamping should
     * not silently change what "no changes since last release" compares to.
     */
    private PromptSpec stampReleasedSemanticHash(PromptSpec released) {
        ReleaseMetadata metadata = released.getReleaseMetadata();
        if (metadata == null) {
            return released;
        }
        if (metadata.releasedSemanticHash() != null) {
            return released;
        }
        String hash = released.computeSemanticHash();
        if (hash == null) {
            return released;
        }
        return released.withReleaseMetadata(metadata.withReleasedSemanticHash(hash));
    }

    @Override
    public PromptSpec persistEvaluatedPrompt(PromptSpec evaluatedPromptSpec) {
        if (evaluatedPromptSpec == null) {
            throw new IllegalArgumentException("Evaluated prompt spec must not be null");
        }
        if (evaluatedPromptSpec.getId() == null || evaluatedPromptSpec.getId().isBlank()) {
            throw new IllegalArgumentException("Evaluated prompt spec id must not be null or blank");
        }

        repository.getLatestVersion(evaluatedPromptSpec.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Prompt %s does not exist".formatted(evaluatedPromptSpec.getId())));

        PromptSpec versioned = increaseRevision(evaluatedPromptSpec);
        PromptSpec hashed = versioned.withSemanticHashComputed();
        repository.storePrompt(hashed);
        return hashed;
    }

    private PromptSpec increaseRevision(PromptSpec promptSpec) {
        return promptSpec.withRevision(promptSpec.getRevision() + 1);
    }

    private List<ChatCompletionRequest.Message> extractMessages(PromptSpec promptSpec) {
        if (promptSpec.getRequest() instanceof ChatCompletionRequest chatRequest) {
            return chatRequest.getMessages();
        }
        return List.of();
    }

    private Map<String, JsonNode> mergeExtensions(Map<String, JsonNode> base, Map<String, JsonNode> updates) {
        if (updates == null || updates.isEmpty()) {
            return base;
        }
        if (base == null || base.isEmpty()) {
            return updates;
        }
        Map<String, JsonNode> merged = new LinkedHashMap<>(base);
        merged.putAll(updates);
        return merged;
    }

    private static ReleaseMetadata requireReleaseMetadata(PromptSpec promptSpec) {
        ReleaseMetadata metadata = promptSpec.getReleaseMetadata();
        if (metadata == null) {
            throw new PromptReleaseException(
                    "Release operation did not provide required release metadata for prompt %s".formatted(promptSpec.getId()));
        }
        return metadata;
    }
}
