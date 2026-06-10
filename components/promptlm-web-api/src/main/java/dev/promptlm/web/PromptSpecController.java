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

import dev.promptlm.domain.AppContext;
import dev.promptlm.domain.projectspec.ProjectSpec;
import dev.promptlm.lifecycle.PromptLifecycleFacade;
import dev.promptlm.domain.events.PromptCreatedEvent;
import dev.promptlm.domain.events.PromptExecutedEvent;
import dev.promptlm.execution.PromptExecutor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import dev.promptlm.domain.promptspec.ChatCompletionRequest;
import dev.promptlm.domain.promptspec.ChatCompletionResponse;
import dev.promptlm.domain.promptspec.Execution;
import dev.promptlm.domain.promptspec.ExecutionKind;
import dev.promptlm.domain.promptspec.PromptSpec;
import dev.promptlm.domain.promptspec.ReleaseMetadata;
import dev.promptlm.domain.promptspec.Request;
import dev.promptlm.pricing.ModelPricingService;
import dev.promptlm.lifecycle.application.PromptSpecAlreadyExistsException;
import dev.promptlm.release.OnInfraFailure;
import dev.promptlm.store.api.PromptStore;
import dev.promptlm.store.api.Revision;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.validation.Valid;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/prompts")
@Tag(name = "Prompt Specifications", description = "API for managing prompt specifications")
@Validated
public class PromptSpecController {

    private static final String PROMPTS_DIRECTORY = "prompts";
    private static final String RELEASE_STATE_HEADER = "X-PromptLM-Release-State";

    private final PromptStore promptStore;
    private final PromptExecutor promptExecutor;
    private final PromptLifecycleFacade promptLifecycleFacade;
    private final AppContext appContext;
    private final PromptSpecLifecycleDeriver lifecycleDeriver;
    private final ModelPricingService modelPricingService;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final SseStatusPublisher sseStatusPublisher;
    private final ApplicationEventPublisher applicationEventPublisher;

    public PromptSpecController(PromptStore promptStore,
                                PromptExecutor promptExecutor,
                                PromptLifecycleFacade promptLifecycleFacade,
                                AppContext appContext,
                                PromptSpecLifecycleDeriver lifecycleDeriver,
                                ModelPricingService modelPricingService,
                                SseEmitterRegistry sseEmitterRegistry,
                                SseStatusPublisher sseStatusPublisher,
                                ApplicationEventPublisher applicationEventPublisher) {
        this.promptStore = promptStore;
        this.promptExecutor = promptExecutor;
        this.promptLifecycleFacade = promptLifecycleFacade;
        this.appContext = appContext;
        this.lifecycleDeriver = lifecycleDeriver;
        this.modelPricingService = modelPricingService;
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.sseStatusPublisher = sseStatusPublisher;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Get the PromptSpec with given id.
     */
    @Operation(summary = "Get prompt specification by ID", 
               description = "Retrieves the latest version of a prompt specification by its unique identifier")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Prompt specification found",
                    content = @Content(mediaType = "application/json", 
                                      schema = @Schema(implementation = PromptSpec.class))),
        @ApiResponse(responseCode = "404", description = "Prompt specification not found")
    })
    @GetMapping(path = "/{promptSpecId}")
    public ResponseEntity<PromptSpecResponseDto> getById(
            @Parameter(description = "Unique identifier of the prompt specification")
            @PathVariable(name = "promptSpecId") String promptSpecId) {
        Optional<PromptSpec> latestVersion = promptStore.getLatestVersion(promptSpecId);
        return ResponseEntity.of(latestVersion.map(spec -> PromptSpecApiView.toApiView(spec, lifecycleDeriver, modelPricingService)));
    }

    /**
     * List all prompts with options for filtering and sorting.
     * @return List of prompt specifications matching the criteria
     */
    @Operation(summary = "List all prompt specifications", 
               description = "Returns a list of prompt specifications")
    @ApiResponse(responseCode = "200", description = "List of prompt specifications",
                content = @Content(mediaType = "application/json",
                        array = @ArraySchema(schema = @Schema(implementation = PromptSpec.class))))
    @GetMapping
    public ResponseEntity<List<PromptSpecResponseDto>> listPromptSpecs() {
        List<PromptSpec> prompts = promptStore.listAllPrompts();
        return ResponseEntity.ok(PromptSpecApiView.toApiView(prompts, lifecycleDeriver, modelPricingService));
    }
    
    /**
     * Create a new {@link PromptSpec}.
     */
    @Operation(summary = "Create a new prompt specification", 
               description = "Creates a new prompt specification with the provided details")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Prompt specification created successfully",
                    content = @Content(mediaType = "application/json", 
                                      schema = @Schema(implementation = PromptSpec.class))),
        @ApiResponse(responseCode = "404", description = "Failed to create prompt specification")
    })
    @PostMapping
    public ResponseEntity<PromptSpecResponseDto> createPromptSpec(
            @RequestBody(description = "Prompt specification creation request details", required = true,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PromptSpecCreationRequest.class)))
            @Valid @org.springframework.web.bind.annotation.RequestBody PromptSpecCreationRequest request) {
        if (!StringUtils.hasText(request.getGroup()) || !StringUtils.hasText(request.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name and group must not be blank");
        }
        PromptSpec promptSpec = toPromptSpec(request);

        try {
            PromptSpec created = promptLifecycleFacade.createPromptSpec(promptSpec);
            return ResponseEntity.ok(PromptSpecApiView.toApiView(created, lifecycleDeriver, modelPricingService));
        } catch (PromptSpecAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    /**
     * Update the given PromptSpec
     */
    @Operation(summary = "Update an existing prompt specification", 
               description = "Updates an existing prompt specification with the provided details")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Prompt specification updated successfully",
                    content = @Content(mediaType = "application/json",
                                      schema = @Schema(implementation = PromptSpec.class))),
        @ApiResponse(responseCode = "404", description = "Prompt specification not found")
    })
    @PutMapping("/{promptSpecId}")
    public ResponseEntity<PromptSpecResponseDto> updatePromptSpec(
            @Parameter(description = "ID of the prompt specification to update")
            @PathVariable("promptSpecId") String promptSpecId,
            @RequestBody(description = "Updated prompt specification details", required = true,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = PromptSpecCreationRequest.class)))
            @Valid @org.springframework.web.bind.annotation.RequestBody PromptSpecCreationRequest request) {
        if (!StringUtils.hasText(request.getGroup()) || !StringUtils.hasText(request.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name and group must not be blank");
        }
        PromptSpec promptSpec = toPromptSpec(request);
        
        // Validate that the ID in the path matches the ID in the request body
        if (promptSpec.getId() != null && !promptSpecId.equals(promptSpec.getId())) {
            throw new IllegalArgumentException("Prompt ID in URL path does not match the ID in the request body");
        }
        
        PromptSpec spec = promptLifecycleFacade.updatePrompt(promptSpecId, promptSpec);
        return ResponseEntity.ok(PromptSpecApiView.toApiView(spec, lifecycleDeriver, modelPricingService));
    }

    @Operation(
        summary = "Get default prompt template",
        description = "Returns a new prompt specification with default values")
        @ApiResponse(
                responseCode = "200",
                description = "Default prompt template",
                content = @Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = @Schema(implementation = PromptSpec.class)
                )
        )
    @GetMapping("/template")
    public ResponseEntity<PromptSpec> getDefaultTemplate() {
        PromptSpec template = promptLifecycleFacade.createDefaultPromptSpec();
        return ResponseEntity.ok(template);
    }

    private PromptSpec toPromptSpec(PromptSpecCreationRequest creationRequest) {
        Map<String, Object> parametersMap = new HashMap<>();
        PromptSpecCreationRequest.Request requestPayload = creationRequest.getRequest();
        if (requestPayload != null && requestPayload.getParameters() != null) {
            PromptSpecCreationRequest.Parameters params = requestPayload.getParameters();
            if (params.getTemperature() != null) parametersMap.put("temperature", params.getTemperature());
            if (params.getTopP() != null) parametersMap.put("topP", params.getTopP());
            if (params.getMaxTokens() != null) parametersMap.put("maxTokens", params.getMaxTokens());
            if (params.getFrequencyPenalty() != null) parametersMap.put("frequencyPenalty", params.getFrequencyPenalty());
            if (params.getPresencePenalty() != null) parametersMap.put("presencePenalty", params.getPresencePenalty());
            if (params.getStream() != null) parametersMap.put("stream", params.getStream());
        }

        String vendor = requestPayload != null ? requestPayload.getVendor() : null;
        if (!StringUtils.hasText(vendor) && creationRequest.getVendorAndModel() != null) {
            vendor = creationRequest.getVendorAndModel().getVendorName();
        }

        String model = requestPayload != null ? requestPayload.getModel() : null;
        if (!StringUtils.hasText(model) && creationRequest.getVendorAndModel() != null) {
            model = creationRequest.getVendorAndModel().getModel();
        }

        String url = requestPayload != null ? requestPayload.getUrl() : null;
        if (!StringUtils.hasText(url) && creationRequest.getVendorAndModel() != null) {
            url = creationRequest.getVendorAndModel().getEndpoint();
        }

        String type = requestPayload != null ? requestPayload.getType() : null;
        if (!StringUtils.hasText(type)) {
            type = ChatCompletionRequest.TYPE;
        }

        String modelSnapshot = requestPayload != null ? requestPayload.getModelSnapshot() : null;

        List<Message> messages = creationRequest.getMessages();
        if ((messages == null || messages.isEmpty()) && requestPayload != null) {
            messages = requestPayload.getMessages();
        }

        Request request = ChatCompletionRequest.builder()
                .withModel(model)
                .withVendor(vendor)
                .withUrl(url)
                .withType(type)
                .withModelSnapshot(modelSnapshot)
                .withMessages(map(messages))
                .withParameters(parametersMap)
                .build();

        String version = StringUtils.hasText(creationRequest.getVersion())
                ? creationRequest.getVersion()
                : "1-SNAPSHOT";

        PromptSpec promptSpec = PromptSpec.builder()
                .withGroup(creationRequest.getGroup())
                .withName(creationRequest.getName())
                .withVersion(version)
                .withRevision(1)
                .withDescription(creationRequest.getDescription())
                .withRequest(request)
                .withRepositoryUrl(creationRequest.getGitHubRepo())
                .withPlaceholders(toPlaceholders(creationRequest))
                .withExtensions(creationRequest.getExtensions())
                .build();
        
        // Set the ID after building if provided
        if (creationRequest.getId() != null) {
            promptSpec = promptSpec.withId(creationRequest.getId());
        }
        
        return promptSpec;
    }

    private List<ChatCompletionRequest.Message> map(List<Message> messages) {
        if (messages == null) {
            return List.of();
        }
        return messages.stream()
                .map(m -> ChatCompletionRequest.Message.builder()
                        .withContent(m.getContent())
                        .withRole(m.getRole().name().toLowerCase())
                        .withName(m.getName())
                        .build())
                .toList();
    }

    private PromptSpec.Placeholders toPlaceholders(PromptSpecCreationRequest request) {
        PromptSpec.Placeholders placeholders = new PromptSpec.Placeholders();
        placeholders.setStartPattern(StringUtils.hasText(request.getPlaceholderStartPattern()) ? request.getPlaceholderStartPattern() : "{{");
        placeholders.setEndPattern(StringUtils.hasText(request.getPlaceholderEndPattern()) ? request.getPlaceholderEndPattern() : "}}");

        Map<String, String> defaults = request.getPlaceholder() != null ? request.getPlaceholder() : Map.of();
        List<PromptSpec.Placeholder> list = defaults.entrySet().stream()
                .map(entry -> new PromptSpec.Placeholder(entry.getKey(), entry.getValue()))
                .toList();
        placeholders.setList(list);
        return placeholders;
    }

    /**
     * Retire a prompt specification by marking it as RETIRED.
     * This preserves the prompt in the store but indicates it should not be used for new applications.
     * 
     * @param promptSpecId The ID of the prompt to retire
     * @param reason Optional reason for retirement
     * @return The updated prompt specification with RETIRED status
     */
    @Operation(summary = "Retire a prompt specification", 
               description = "Marks a prompt specification as retired with an optional retirement reason")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Prompt specification retired successfully",
                    content = @Content(mediaType = "application/json", 
                                      schema = @Schema(implementation = PromptSpec.class))),
        @ApiResponse(responseCode = "404", description = "Prompt specification not found")
    })
    @PutMapping("/{promptSpecId}/retire")
    public ResponseEntity<PromptSpec> retirePrompt(
            @Parameter(description = "ID of the prompt specification to retire") 
            @PathVariable("promptSpecId") String promptSpecId,
            
            @Parameter(description = "Optional reason for retiring this prompt specification") 
            @RequestParam(value = "reason", required = false) String reason) {
        
        // Get the latest version of the prompt
        Optional<PromptSpec> promptOpt = promptStore.getLatestVersion(promptSpecId);
        if (promptOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        // Mark the prompt as retired
        PromptSpec prompt = promptOpt.get();
        PromptSpec retiredPrompt = prompt
                .withStatus(PromptSpec.PromptStatus.RETIRED)
                .withRetiredAt(LocalDateTime.now())
                .withRetiredReason(reason);
        
        // Update the prompt in the store
        promptStore.updatePrompt(promptSpecId, retiredPrompt);
        
        // Return the updated prompt
        return promptStore.getLatestVersion(promptSpecId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * Get statistics about prompts in the store
     * 
     * @return PromptStats object with counts and distributions
     */
    @Operation(summary = "Get prompt statistics", 
               description = "Returns statistics about prompts in the store including counts and distributions")
    @ApiResponse(responseCode = "200", description = "Prompt statistics retrieved successfully",
                content = @Content(mediaType = "application/json", 
                                  schema = @Schema(implementation = PromptStats.class)))
    @GetMapping("/stats")
    public PromptStats getPromptStats() {
        // Get all prompts including retired ones
        List<PromptSpec> allPrompts = promptStore.listAllPrompts(true);

        // Count active and retired prompts
        long activeCount = allPrompts.stream()
                .filter(p -> p.getStatus() == null || p.getStatus() == PromptSpec.PromptStatus.ACTIVE)
                .count();
        
        long retiredCount = allPrompts.stream()
                .filter(p -> p.getStatus() == PromptSpec.PromptStatus.RETIRED)
                .count();
        
        // Count prompts by group
        Map<String, Long> countByGroup = allPrompts.stream()
                .filter(p -> p.getGroup() != null && !p.getGroup().isEmpty())
                .collect(Collectors.groupingBy(PromptSpec::getGroup, Collectors.counting()));

        ProjectSpec activeProject = appContext.getActiveProject();
        int activeProjects = appContext.getProjects().size();
        Instant lastUpdated = activeProject != null ? resolveActiveProjectLastUpdatedFromGitMetadata(activeProject) : null;

        return new PromptStats(
                allPrompts.size(),
                activeCount,
                retiredCount,
                countByGroup,
                activeProjects,
                lastUpdated
        );
    }
    
    /**
     * Get all available prompt groups for filtering
     * 
     * @param includeRetired Whether to include groups that only contain retired prompts
     * @return List of unique group names
     */
    @Operation(summary = "Get all prompt groups", 
               description = "Returns a list of all unique prompt group names")
    @ApiResponse(responseCode = "200", description = "List of prompt groups",
                content = @Content(mediaType = "application/json", 
                                  schema = @Schema(type = "array", implementation = String.class)))
    @GetMapping("/groups")
    public List<String> getPromptGroups(
            @Parameter(description = "Include groups that only contain retired prompts",
                       schema = @Schema(type = "boolean"))
            @RequestParam(value = "includeRetired", required = false, defaultValue = "false") boolean includeRetired) {
        List<PromptSpec> prompts = promptStore.listAllPrompts(includeRetired);

        // Extract unique groups
        return prompts.stream()
                .map(PromptSpec::getGroup)
                .filter(group -> group != null && !group.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }
    
    /**
     * Create a new release for a prompt specification.
     * This will create a new version of the prompt with an incremented version number.
     *
     * @param promptSpecId The ID of the prompt to release.
     * @return The release operation response payload.
     */
    @Operation(
            summary = "Release a new version of a prompt",
            description = "Requests release for a prompt specification. In direct mode the response state is released. In pr_two_phase mode the response state is requested until /release/complete is called. The pre-release-execute gate runs the spec defaults server-side before promotion; failures yield 422 (PRE_RELEASE_PROMPT_FAILURE) or 503 (PRE_RELEASE_INFRA_FAILURE) unless onInfraFailure=record is supplied.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Release operation response as prompt specification. Header X-PromptLM-Release-State mirrors extensions.x-promptlm.release.state (requested|released).",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PromptSpec.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Prompt specification with the given ID not found."
                    ),
                    @ApiResponse(
                            responseCode = "422",
                            description = "Pre-release execution failed for prompt-class reasons (PRE_RELEASE_PROMPT_FAILURE)."
                    ),
                    @ApiResponse(
                            responseCode = "503",
                            description = "Pre-release execution failed for infrastructure-class reasons (PRE_RELEASE_INFRA_FAILURE). Retry, or repeat the request with onInfraFailure=record to release with the failure recorded."
                    )
            }
    )
    @PostMapping("/{promptSpecId}/release")
    public ResponseEntity<PromptSpec> releasePrompt(
            @Parameter(description = "The unique identifier of the prompt specification to release.")
            @PathVariable("promptSpecId") String promptSpecId,
            @Parameter(description = "Behaviour when the pre-release-execute gate hits an infrastructure-class failure. 'reject' (default) soft-blocks the release; 'record' records the failed execution and proceeds.",
                    schema = @Schema(allowableValues = {"reject", "record"}))
            @RequestParam(value = "onInfraFailure", required = false) String onInfraFailure) {
        Optional<PromptSpec> latestVersion = promptStore.getLatestVersion(promptSpecId);
        if (latestVersion.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        OnInfraFailure parsed = parseOnInfraFailure(onInfraFailure);
        PromptSpec releaseResponse = promptLifecycleFacade.release(promptSpecId, parsed);
        return releaseResponse(releaseResponse);
    }

    @Hidden
    @PostMapping("/{group}/{name}/release")
    public ResponseEntity<PromptSpec> releasePromptByGroupAndName(
            @PathVariable("group") String group,
            @PathVariable("name") String name,
            @RequestParam(value = "onInfraFailure", required = false) String onInfraFailure) {
        return releasePrompt(group + "/" + name, onInfraFailure);
    }

    private static OnInfraFailure parseOnInfraFailure(String value) {
        if (value == null || value.isBlank()) {
            return OnInfraFailure.REJECT;
        }
        try {
            return OnInfraFailure.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported onInfraFailure value '%s'. Allowed: reject, record.".formatted(value));
        }
    }

    @Operation(
            summary = "Complete a pending prompt release",
            description = "Finalizes a previously requested PR-mode release after validating the referenced pull request has been merged into main. Header X-PromptLM-Release-State is released on success.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Completed release response.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PromptSpec.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Prompt specification with the given ID not found."
                    )
            }
    )
    @PostMapping("/{promptSpecId}/release/complete")
    public ResponseEntity<PromptSpec> completeReleasePrompt(
            @Parameter(description = "The unique identifier of the prompt specification to complete.")
            @PathVariable("promptSpecId") String promptSpecId,
            @Parameter(description = "Pull request number or URL for the pending release request.")
            @RequestParam("pr") String pullRequestReference) {
        Optional<PromptSpec> latestVersion = promptStore.getLatestVersion(promptSpecId);
        if (latestVersion.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        PromptSpec releaseResponse = promptLifecycleFacade.completeRelease(promptSpecId, pullRequestReference);
        return releaseResponse(releaseResponse);
    }

    @Hidden
    @PostMapping("/{group}/{name}/release/complete")
    public ResponseEntity<PromptSpec> completeReleasePromptByGroupAndName(
            @PathVariable("group") String group,
            @PathVariable("name") String name,
            @RequestParam("pr") String pullRequestReference) {
        return completeReleasePrompt(group + "/" + name, pullRequestReference);
    }

    /**
     * List the git-history revisions for a prompt, newest first.
     */
    @Operation(
            summary = "List revisions for a prompt specification",
            description = "Returns a newest-first list of revisions for the given prompt, "
                    + "derived from the active project's git history. Each revision includes "
                    + "metadata (rev label, sha, author, when, msg, kind, optional tag) plus "
                    + "the full PromptSpec snapshot at that commit when it can be deserialized "
                    + "against the current schema (otherwise spec is null). Responses carry a "
                    + "weak ETag derived from the newest revision's commit SHA; clients can "
                    + "send `If-None-Match` to receive 304 Not Modified when the history is "
                    + "unchanged.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Revisions list, newest first.",
                            content = @Content(
                                    mediaType = "application/json",
                                    array = @ArraySchema(schema = @Schema(implementation = Revision.class))
                            )
                    ),
                    @ApiResponse(
                            responseCode = "304",
                            description = "Not Modified — the supplied If-None-Match matches the current ETag."
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid prompt id (unsafe path segments)."
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "No revisions found for the given prompt."
                    )
            }
    )
    @GetMapping("/{group}/{name}/revisions")
    public ResponseEntity<List<Revision>> getRevisionsByGroupAndName(
            @Parameter(description = "Prompt group") @PathVariable("group") String group,
            @Parameter(description = "Prompt name") @PathVariable("name") String name,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        List<Revision> revisions;
        try {
            revisions = promptStore.listRevisions(group, name);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        if (revisions == null || revisions.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String etag = revisionsEtag(revisions);
        if (etagMatches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(revisions);
    }

    @Hidden
    @GetMapping("/{promptSpecId}/revisions")
    public ResponseEntity<List<Revision>> getRevisions(
            @PathVariable("promptSpecId") String promptSpecId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        if (promptSpecId == null || promptSpecId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "promptSpecId must not be blank");
        }
        int slash = promptSpecId.indexOf('/');
        if (slash <= 0 || slash == promptSpecId.length() - 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "promptSpecId must be of the form 'group/name'");
        }
        String group = promptSpecId.substring(0, slash);
        String name = promptSpecId.substring(slash + 1);
        return getRevisionsByGroupAndName(group, name, ifNoneMatch);
    }

    private static String revisionsEtag(List<Revision> revisions) {
        // Weak ETag tied to the newest commit SHA — sufficient because a new
        // commit on the prompt path produces a new newest sha and invalidates.
        return "W/\"" + revisions.get(0).sha() + "\"";
    }

    private static boolean etagMatches(String ifNoneMatch, String currentEtag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        // RFC 7232: `If-None-Match` may be a comma-separated list of ETags or
        // the wildcard `*`. Handle both, and treat strong/weak forms as a
        // match when the opaque-tag portion is identical.
        String normalizedCurrent = stripWeakPrefix(currentEtag);
        for (String candidate : ifNoneMatch.split(",")) {
            String trimmed = candidate.trim();
            if ("*".equals(trimmed)) {
                return true;
            }
            if (stripWeakPrefix(trimmed).equals(normalizedCurrent)) {
                return true;
            }
        }
        return false;
    }

    private static String stripWeakPrefix(String etag) {
        if (etag.startsWith("W/")) {
            return etag.substring(2);
        }
        return etag;
    }

    /**
     * Execute a prompt specification and return the result with the LLM response
     * 
     * @param request The execute prompt request containing the prompt specification to execute
     * @return The executed prompt specification with the response from the LLM
     */
    @Operation(summary = "Execute a prompt specification",
               description = "Executes the given prompt specification with the configured LLM and returns the result")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Prompt executed successfully",
                    content = @Content(mediaType = "application/json",
                                      schema = @Schema(implementation = PromptSpec.class))),
        @ApiResponse(responseCode = "400", description = "Invalid prompt specification"),
        @ApiResponse(responseCode = "500", description = "Error executing the prompt")
    })
    @PostMapping("/execute")
    public ResponseEntity<?> executePrompt(
            @RequestBody(description = "Prompt specification to execute", required = true,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ExecutePromptRequest.class)))
            @Valid @org.springframework.web.bind.annotation.RequestBody ExecutePromptRequest request) {
        if (request.getPromptSpec() == null) {
            return ResponseEntity.badRequest().build();
        }
        PromptSpec promptSpec = request.getPromptSpec();
        try {
            PromptSpec executedSpec = promptExecutor.runPromptAndAttachResponse(promptSpec);
            return ResponseEntity.ok(PromptSpecApiView.toApiView(executedSpec, lifecycleDeriver, modelPricingService));
        } catch (RuntimeException exception) {
            throw mapPromptExecutionException(promptSpec.getId(), exception);
        }
    }

    /**
     * Execute a persisted prompt specification by ID and return the result with the LLM response.
     * This endpoint loads the stored prompt, executes it, and returns the executed spec with its persisted ID.
     *
     * @param promptSpecId The ID of the prompt specification to execute
     * @return The executed prompt specification with the response from the LLM
     */
    @Operation(summary = "Execute a stored prompt specification",
               description = "Loads the stored prompt specification by ID, executes it, and returns the result")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Prompt executed successfully",
                    content = @Content(mediaType = "application/json",
                                      schema = @Schema(implementation = PromptSpec.class))),
        @ApiResponse(responseCode = "404", description = "Prompt specification not found"),
        @ApiResponse(responseCode = "500", description = "Error executing the prompt")
    })
    @PostMapping("/{promptSpecId}/execute")
    public ResponseEntity<?> executeStoredPrompt(
            @Parameter(description = "ID of the prompt specification to execute", required = true)
            @PathVariable("promptSpecId") String promptSpecId,
            @RequestBody(description = "Prompt specification to execute. When supplied, the contained "
                    + "`promptSpec` is executed in place of the stored YAML. This lets the editor run "
                    + "the current form state without saving (issue #183). The path id is always "
                    + "authoritative; a body id that mismatches the path id returns 400. Draft "
                    + "executions are ephemeral and are NOT recorded in the prompt's execution history.",
                    required = false,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ExecutePromptRequest.class)))
            @Valid @org.springframework.web.bind.annotation.RequestBody(required = false) ExecutePromptRequest request) {
        Optional<PromptSpec> latestStoredSpec = promptStore.getLatestVersion(promptSpecId);
        if (latestStoredSpec.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        PromptSpec storedSpec = latestStoredSpec.get();
        PromptSpec promptSpecToExecute = storedSpec;

        if (request != null && request.getPromptSpec() != null) {
            PromptSpec requestPromptSpec = request.getPromptSpec();
            if (requestPromptSpec.getId() != null && !promptSpecId.equals(requestPromptSpec.getId())) {
                return ResponseEntity.badRequest().build();
            }
            // Execute the spec from the request body (issue #183). The path id is
            // authoritative — force it onto the spec so any downstream code that
            // reads the id sees the canonical one.
            promptSpecToExecute = requestPromptSpec.withId(promptSpecId);
        }

        // Issue #140 / #183 reconciliation: the editor knows whether the user
        // has unsaved edits — it tells us via the `draft` flag. A clean Run
        // (draft=false, the default) records a MANUAL Execution against the
        // stored prompt; a run with unsaved edits (draft=true) is ephemeral so
        // the user can experiment without polluting the dev branch.
        //
        // Earlier code inferred draft-ness from
        // `PromptSpec#hasSemanticChangesComparedTo`, but that proved fragile:
        // the TS payload differs from the stored YAML in a hundred small ways
        // (role casing, normalisation, synthesised system messages, ...), so
        // a clean Run was constantly misclassified and the MANUAL Execution
        // silently dropped. See HappyPathUserJourneyTest
        // #runPromptPersistsManualExecution.
        boolean isDraftExecution = request != null && request.isDraft();

        if (promptSpecToExecute.getId() == null || promptSpecToExecute.getId().isBlank()) {
            promptSpecToExecute = promptSpecToExecute.withId(promptSpecId);
        }
        Instant runStart = Instant.now();
        try {
            PromptSpec executedSpec = promptExecutor.runPromptAndAttachResponse(promptSpecToExecute);
            if (isDraftExecution) {
                // Draft executions are ephemeral: the response is returned to the
                // UI so it can be displayed, but nothing is written to history.
                // History only ever contains runs of actually-stored content
                // (D-183-5; reverses the consequence note in D-183-3).
                return ResponseEntity.ok(PromptSpecApiView.toApiView(executedSpec, lifecycleDeriver, modelPricingService));
            }
            // No-body fallback (called by PromptDetail.tsx): the stored spec was
            // executed, so the run is genuine and is recorded against the
            // stored revision.
            Execution devRun = buildDevExecution(executedSpec, storedSpec.getRevision(), runStart);
            PromptSpec persisted = promptLifecycleFacade.recordExecution(promptSpecId, devRun);
            return ResponseEntity.ok(PromptSpecApiView.toApiView(persisted, lifecycleDeriver, modelPricingService));
        } catch (RuntimeException exception) {
            throw mapPromptExecutionException(promptSpecId, exception);
        }
    }

    private Execution buildDevExecution(PromptSpec executedSpec, int revision, Instant runStart) {
        Instant now = Instant.now();
        long latencyMs = java.time.Duration.between(runStart, now).toMillis();
        // Issue #182: persist vendor-reported token counts on every recorded
        // execution. USD cost is intentionally NOT persisted here — it depends
        // on the operator-managed per-model pricing table which is mutable
        // external state. Persisting cost would silently invalidate every
        // historical record the moment application.yml is edited. The web view
        // layer derives `costUsd` on read instead; see PromptSpecApiView.
        Integer tokensIn = null;
        Integer tokensOut = null;
        if (executedSpec.getResponse() instanceof ChatCompletionResponse chatResponse) {
            ChatCompletionResponse.Usage usage = chatResponse.getUsage();
            if (usage != null) {
                tokensIn = usage.getInputTokens();
                tokensOut = usage.getOutputTokens();
            }
        }
        return new Execution(
                UUID.randomUUID().toString(),
                now,
                executedSpec.getResponse(),
                null,
                null,
                latencyMs,
                tokensIn,
                tokensOut,
                null,
                null,
                Integer.toString(revision),
                null,
                true,
                null,
                ExecutionKind.MANUAL,
                null);
    }

    /**
     * Repo-history endpoint backing the Test tab's "View history →" flyover.
     *
     * <p>Returns executions captured against revisions of the prompt other than the current
     * latest, paginated and filterable by revision and outcome. Same-revision-but-shape-divergent
     * executions on the latest version are intentionally not returned: per issue #100, the live
     * executions strip applies request-shape diffing client-side.
     */
    @Operation(summary = "Get older executions for a prompt",
               description = "Returns executions captured against earlier revisions of the prompt "
                       + "(those not in the current latest version's executions list). Sorted newest "
                       + "first. Filters: revision, status (ok|fail). Paginated.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Page of historic executions",
                    content = @Content(mediaType = "application/json",
                                      schema = @Schema(implementation = RepoHistoryPage.class))),
        @ApiResponse(responseCode = "400", description = "Invalid query parameters"),
        @ApiResponse(responseCode = "404", description = "Prompt specification not found")
    })
    @GetMapping("/{promptSpecId}/history")
    public ResponseEntity<RepoHistoryPage> getRepoHistory(
            @Parameter(description = "ID of the prompt specification (group/name composite)")
            @PathVariable("promptSpecId") String promptSpecId,
            @Parameter(description = "Filter to executions with this revision identifier")
            @RequestParam(value = "revision", required = false) String revision,
            @Parameter(description = "Filter by outcome: 'ok' or 'fail'")
            @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "1-indexed page number; values < 1 are clamped to 1")
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @Parameter(description = "Page size; clamped to [1, 200]; non-positive values yield the default of 50")
            @RequestParam(value = "pageSize", required = false, defaultValue = "50") int pageSize) {

        Optional<PromptSpec> latestVersion = promptStore.getLatestVersion(promptSpecId);
        if (latestVersion.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RepoHistoryFilter filter = parseHistoryFilter(revision, status);
        List<PromptSpec> versions = promptStore.listVersions(promptSpecId);
        RepoHistoryPage historyPage = RepoHistoryAssembler.assemble(
                latestVersion.get(), versions, filter, page, pageSize);
        return ResponseEntity.ok(historyPage);
    }

    @Hidden
    @GetMapping("/{group}/{name}/history")
    public ResponseEntity<RepoHistoryPage> getRepoHistoryByGroupAndName(
            @PathVariable("group") String group,
            @PathVariable("name") String name,
            @RequestParam(value = "revision", required = false) String revision,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "pageSize", required = false, defaultValue = "50") int pageSize) {
        return getRepoHistory(group + "/" + name, revision, status, page, pageSize);
    }

    private static RepoHistoryFilter parseHistoryFilter(String revision, String status) {
        Optional<String> revisionFilter = StringUtils.hasText(revision)
                ? Optional.of(revision)
                : Optional.empty();
        Optional<Boolean> statusFilter;
        if (!StringUtils.hasText(status)) {
            statusFilter = Optional.empty();
        } else {
            String normalised = status.trim().toLowerCase(java.util.Locale.ROOT);
            switch (normalised) {
                case "ok" -> statusFilter = Optional.of(Boolean.TRUE);
                case "fail" -> statusFilter = Optional.of(Boolean.FALSE);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "status must be 'ok' or 'fail'");
            }
        }
        return new RepoHistoryFilter(revisionFilter, statusFilter);
    }

    private Instant resolveActiveProjectLastUpdatedFromGitMetadata(ProjectSpec activeProject) {
        Path normalizedRepoDir = activeProject.getRepoDir().toAbsolutePath().normalize();

        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(normalizedRepoDir.toFile())) {
            Iterable<RevCommit> promptCommits = git.log()
                    .addPath(PROMPTS_DIRECTORY)
                    .setMaxCount(1)
                    .call();

            java.util.Iterator<RevCommit> commits = promptCommits.iterator();
            if (commits.hasNext()) {
                return instantFromCommit(commits.next());
            }
            throw new IllegalStateException(
                    "No git commit found for prompts directory in active project repository: " + normalizedRepoDir);
        } catch (NoHeadException e) {
            throw new IllegalStateException("Active project repository has no commits: " + normalizedRepoDir, e);
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException("Failed to resolve active-project git metadata: " + normalizedRepoDir, e);
        }
    }

    private ResponseEntity<PromptSpec> releaseResponse(PromptSpec releaseResponse) {
        ReleaseMetadata releaseMetadata = releaseResponse.getReleaseMetadata();
        if (releaseMetadata == null || !StringUtils.hasText(releaseMetadata.state())) {
            throw new IllegalStateException("Release response is missing required release metadata state");
        }
        return ResponseEntity.ok()
                .header(RELEASE_STATE_HEADER, releaseMetadata.state())
                .body(releaseResponse);
    }

    private Instant instantFromCommit(RevCommit commit) {
        return Instant.ofEpochSecond(commit.getCommitTime());
    }

    /**
     * Open an SSE stream for the prompt-execution channel (issue #352). The UI subscribes
     * after Save to receive {@code executing | executed | failed | pushed} status updates.
     */
    @Hidden
    @GetMapping(value = "/{promptSpecId}/execution/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter registerExecutionEvents(@PathVariable("promptSpecId") String promptSpecId) {
        String key = PromptExecutionSseListener.channelKey(promptSpecId);
        SseEmitter emitter = new SseEmitter(0L);
        sseEmitterRegistry.register(key, emitter);
        sseStatusPublisher.connected(
                key,
                PromptExecutionSseListener.OPERATION,
                Map.of("promptId", promptSpecId));
        return emitter;
    }

    /**
     * Re-run the LLM execution against the locally-stored spec. Used by the editor's Retry
     * button after a failed execution (issue #352). Returns 202 — the actual run + push
     * happen asynchronously via the existing event chain. Progress is reported over the
     * prompt-execution SSE channel.
     */
    @Operation(summary = "Re-execute a stored prompt", description = "Re-fires the LLM execution against the locally-stored spec and pushes the result on success. Progress is delivered via the prompt-execution SSE channel.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Re-execution accepted"),
            @ApiResponse(responseCode = "404", description = "Prompt specification not found")
    })
    @PostMapping("/{promptSpecId}/execute/retry")
    public ResponseEntity<Void> retryExecution(
            @Parameter(description = "ID of the prompt specification to re-execute") @PathVariable("promptSpecId") String promptSpecId) {
        Optional<PromptSpec> stored = promptStore.getLatestVersion(promptSpecId);
        if (stored.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Re-publish a PromptCreatedEvent so the existing async listener chain
        // (PromptExecutionListener -> PromptPushListener) runs end-to-end with the
        // local-only commit. Reusing PromptCreatedEvent keeps the contract minimal and
        // avoids a parallel "retry" code path.
        applicationEventPublisher.publishEvent(new PromptCreatedEvent(stored.get()));
        return ResponseEntity.accepted().build();
    }

    /**
     * Re-fire only the deferred amend+push against the locally-stored spec — used by the
     * editor's Retry-push affordance after a {@code push-failed} SSE status (issue #361).
     *
     * <p>This does NOT re-run the LLM. The stored spec already carries the attached
     * response from a successful execution; what failed is the remote push. Republishing
     * {@link PromptExecutedEvent} re-enters the existing {@code PromptPushListener} flow,
     * which calls {@code amendAndPushHead} again against the preserved local commit.
     */
    @Operation(summary = "Retry the deferred push for a stored prompt",
            description = "Re-fires the amend+push for a prompt whose previous push failed (issue #361). "
                    + "Does NOT re-run the LLM — only the locally-stored spec's existing response is pushed. "
                    + "Progress is delivered via the prompt-execution SSE channel.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Push retry accepted"),
            @ApiResponse(responseCode = "404", description = "Prompt specification not found")
    })
    @PostMapping("/{promptSpecId}/push/retry")
    public ResponseEntity<Void> retryPush(
            @Parameter(description = "ID of the prompt specification whose push should be retried")
            @PathVariable("promptSpecId") String promptSpecId) {
        Optional<PromptSpec> stored = promptStore.getLatestVersion(promptSpecId);
        if (stored.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Re-fire only the push leg: PromptPushListener handles PromptExecutedEvent by
        // calling amendAndPushHead on the supplied spec. The LLM is not re-invoked.
        applicationEventPublisher.publishEvent(new PromptExecutedEvent(stored.get()));
        return ResponseEntity.accepted().build();
    }

    @Hidden
    @PostMapping("/{group}/{name}/push/retry")
    public ResponseEntity<Void> retryPushByGroupAndName(
            @PathVariable("group") String group,
            @PathVariable("name") String name) {
        return retryPush(group + "/" + name);
    }

    private PromptExecutionException mapPromptExecutionException(String promptId, RuntimeException exception) {
        if (exception instanceof IllegalArgumentException
                || exception instanceof IllegalStateException
                || exception instanceof UnsupportedOperationException) {
            return PromptExecutionException.badRequest(promptId, exception);
        }
        return PromptExecutionException.internalServerError(promptId, exception);
    }

}
