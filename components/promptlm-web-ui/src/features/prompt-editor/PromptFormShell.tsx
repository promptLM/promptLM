// Copyright 2025 promptLM
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/**
 * v2 prompt editor shell — renders `PromptFormPage` from `@promptlm/ui` and
 * bridges it to the existing draft state machine (`usePromptEditorDraft`)
 * and persistence actions (`savePromptDraftAction`, `releasePromptAction`).
 *
 * Implements issue #93. Replaces the cards-stack layout that ships in the
 * legacy `PromptEditorPage` for both `/prompts/new` and `/prompts/:id/edit`.
 *
 * Differences vs. the legacy editor (deliberate, per the design spec):
 * - No tabs surface — the form is a single page.
 * - No Preview / Test / History panels here. Test execution lives behind
 *   `executePrompt` and is reachable from a separate route in the new design.
 * - Tool configs (MCP mocks) and placeholder schema fields (type/required/
 *   description) are surfaced in the rail UI but are client-side-only until
 *   backend support lands; user edits don't round-trip yet.
 *
 * Issue #185 — adds:
 * - Dirty detection via `usePromptFormDirty` (passed to `PromptFormPage` so
 *   the sticky header can render the "Modified" chip).
 * - A `beforeunload` listener that prompts on tab close / reload while dirty.
 * - A `popstate` interceptor (with a sentinel history entry) that catches
 *   browser back and routes the user through `UnsavedChangesDialog`.
 * - A guarded Cancel button.
 * Sidebar/header nav links are *not* guarded by this PR — the app uses
 * `BrowserRouter` (not a data router) so `useBlocker` is unavailable.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { useNavigate } from 'react-router-dom';

import {
  PLACEHOLDER_INSERT_NO_CARET_HINT,
  buildPlaceholderToken,
  insertPlaceholderAtCaret,
  type CaretSelection,
} from './insertPlaceholderAtCaret';

import {
  PromptFormPage,
  type EditorRunRecord,
  type PromptFormContext,
  type PromptFormDraft,
  type PromptFormToolConfig,
} from '@promptlm/ui';

import type { PromptEditorMode, PromptEditorState } from './types';
import {
  createEmptyPromptDraft,
  createPromptDraftFromPrompt,
  usePromptEditorDraft,
} from './draftState';
import { buildEditorRunRequest } from './buildEditorRunRequest';
import { releasePromptAction, savePromptDraftAction } from './editorActions';
import { selectRevisionId } from './selectRevisionId';
import { buildViewOnRemoteUrl } from './buildViewOnRemoteUrl';
import { selectRunCost } from './selectRunCost';
import { useTokenEstimate } from './useTokenEstimate';
import { usePromptEditorData } from './usePromptEditorData';
import { usePromptFormDirty } from './dirtyState';
import { UnsavedChangesDialog } from './UnsavedChangesDialog';
import { usePromptExecutionSubscription } from './usePromptExecutionSubscription';
import { PromptExecutionStatusPanel } from './PromptExecutionStatusPanel';
import { retryPromptExecution } from './retryPromptExecution';
import { useCapabilities } from '@/api/hooks';
import { useGeneratedApiClient } from '@api-common/generatedClientProvider';
import { featureFlags } from '@/lib/featureFlags';
import type { PromptDraftInput } from '@/api/promptPayloads';

type PromptFormShellProps = {
  mode: PromptEditorMode;
  promptId: string | null;
};

const FALLBACK_VERSION = '0.1.0';
const FALLBACK_REVISION = 'r1';

const toFormVendor = (raw: string): string => raw.toLowerCase();

const toFormDraft = (
  state: ReturnType<typeof usePromptEditorDraft>['state'],
  toolConfigs: PromptFormToolConfig[],
): PromptFormDraft => {
  const { draft } = state;
  const params = draft.request.parameters ?? {};
  return {
    name: draft.name,
    group: draft.group,
    description: draft.description ?? '',
    request: {
      type: 'chat',
      vendor: toFormVendor(draft.request.vendor ?? ''),
      model: draft.request.model ?? '',
      modelSnapshot: draft.request.modelSnapshot ?? '',
      parameters: {
        temperature: typeof params.temperature === 'number' ? params.temperature : 0.2,
        topP: typeof params.topP === 'number' ? params.topP : 1.0,
        maxTokens: typeof params.maxTokens === 'number' ? params.maxTokens : 1024,
        frequencyPenalty:
          typeof params.frequencyPenalty === 'number' ? params.frequencyPenalty : 0,
        presencePenalty:
          typeof params.presencePenalty === 'number' ? params.presencePenalty : 0,
      },
      messages: draft.request.messages.map((m) => ({
        role: m.role,
        content: m.content,
        name: m.name,
      })),
    },
    placeholders: {
      startPattern: draft.placeholders.startPattern ?? '{{',
      endPattern: draft.placeholders.endPattern ?? '}}',
      // Existing schema only carries name + default value. Map value into the
      // form's `description` slot since that's the closest match. Type and
      // required are stubbed until the schema gains them; their UI edits are
      // dropped on persist (acceptable per playbook §Notes).
      list: draft.placeholders.list.map((ph) => ({
        name: ph.name,
        type: 'string',
        required: false,
        description: ph.value ?? '',
      })),
    },
    toolConfigs,
    evaluations: (draft.evaluations ?? []).map((ev) => ({
      evaluator: ev.evaluator,
      type: ev.type as PromptFormDraft['evaluations'][number]['type'],
      description: ev.description ?? '',
    })),
  };
};

/**
 * Apply a form-shaped draft back onto the existing `PromptDraftInput`. Lossy
 * by design for the fields the existing schema doesn't carry — see comments.
 */
const applyFormDraft = (
  prev: PromptDraftInput,
  next: PromptFormDraft,
): PromptDraftInput => ({
  ...prev,
  name: next.name,
  group: next.group,
  description: next.description,
  request: {
    ...prev.request,
    vendor: next.request.vendor,
    model: next.request.model,
    modelSnapshot: next.request.modelSnapshot,
    parameters: {
      ...prev.request.parameters,
      temperature: next.request.parameters.temperature,
      topP: next.request.parameters.topP,
      maxTokens: next.request.parameters.maxTokens,
      frequencyPenalty: next.request.parameters.frequencyPenalty,
      presencePenalty: next.request.parameters.presencePenalty,
    },
    messages: next.request.messages.map((m, index) => {
      const previousMessage = prev.request.messages[index];
      return {
        id: previousMessage?.id ?? `msg-${Date.now()}-${index}`,
        role: m.role,
        content: m.content,
        name: m.name,
      };
    }),
  },
  placeholders: {
    ...prev.placeholders,
    startPattern: next.placeholders.startPattern,
    endPattern: next.placeholders.endPattern,
    // Persist name and the description as the default-value slot — type and
    // required are intentionally dropped (no schema support yet).
    list: next.placeholders.list.map((ph) => ({
      name: ph.name,
      value: ph.description,
    })),
    defaults: Object.fromEntries(
      next.placeholders.list
        .filter((ph) => ph.name.length > 0)
        .map((ph) => [ph.name, ph.description] as const),
    ),
  },
  evaluations: next.evaluations.map((ev) => ({
    evaluator: ev.evaluator,
    type: ev.type,
    description: ev.description,
  })),
});

// Issue #185 — popstate sentinel marker.
const POPSTATE_SENTINEL_KEY = '__promptlmDirtyGuard';

// Issue #241 — sentinel value used by the hydration ref to denote "we
// already hydrated an empty draft". Distinct from `null` (no hydration yet
// at all) so the once-only guard can tell them apart.
const EMPTY_HYDRATION_TOKEN: unique symbol = Symbol('emptyHydration');

export const PromptFormShell = ({ mode, promptId }: PromptFormShellProps) => {
  const navigate = useNavigate();
  const data = usePromptEditorData({ mode, promptId });
  const capabilities = useCapabilities();
  const editor = usePromptEditorDraft(useMemo(() => createEmptyPromptDraft(), []));
  const { replaceState, setRepositoryUrl } = editor;
  const { promptSpecs } = useGeneratedApiClient();
  const [createdPromptId, setCreatedPromptId] = useState<string | null>(null);
  const [isReleasing, setIsReleasing] = useState(false);
  const [validationRequested, setValidationRequested] = useState(false);
  const [toolConfigs, setToolConfigs] = useState<PromptFormToolConfig[]>([]);
  const [editorRunState, setEditorRunState] = useState<'idle' | 'running'>('idle');
  const [lastEditorRun, setLastEditorRun] = useState<EditorRunRecord | null>(null);
  // Issue #187: track the last caret/selection emitted by MessagesEditor so a
  // click on a placeholder's Insert button knows where to splice the token.
  // We keep the value in a ref (not state) to avoid re-renders on every key
  // stroke; reads only happen inside event handlers.
  const caretSelectionRef = useRef<CaretSelection | null>(null);
  const [placeholderInsertHint, setPlaceholderInsertHint] = useState<string | null>(null);
  // Bump on every hint set so identical messages still re-trigger the auto-
  // clear timer (e.g. user clicks Insert twice with no caret).
  const placeholderInsertHintNonce = useRef(0);

  // Issue #185 — baseline (last persisted snapshot) tracking + dialog state.
  const [baseline, setBaseline] = useState<PromptEditorState | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const pendingNavigationRef = useRef<(() => void) | null>(null);

  // Issue #360 — subscribe to the prompt-execution SSE channel after each
  // successful save. The hook owns subscription lifecycle (strict-mode safe)
  // and exposes a small state machine driving `PromptExecutionStatusPanel`.
  const executionSubscription = usePromptExecutionSubscription();
  const [isRetryingExecution, setIsRetryingExecution] = useState(false);
  // Last prompt id whose execution we tracked — used by Retry to resubscribe
  // and by the auto-dismiss timer on the transient `pushed` state.
  const lastExecutionPromptIdRef = useRef<string | null>(null);

  // Issue #241 — guard against re-hydration clobbering user input. The
  // hydration effect below depends on `data.prompt` / `data.promptTemplate`;
  // a late re-delivery of either (React-Query refetch, provider re-mount,
  // context churn from `useGeneratedApiClient` if the config reference
  // shifts) would otherwise call `replaceState(template)` again and wipe
  // anything the user has typed since the first hydration. We track the
  // identity of what we hydrated *from* so re-runs with the same source
  // are no-ops; switching mode or prompt id resets the guard so the next
  // route genuinely re-hydrates.
  const hydrationSourceRef = useRef<unknown>(null);
  useEffect(() => {
    hydrationSourceRef.current = null;
  }, [mode, promptId]);

  // Hydrate draft on load. Depend only on the replaceState callback (stable
  // reference from the useReducer dispatch) — depending on `editor` triggers
  // an infinite loop because the memoised actions object changes per render.
  useEffect(() => {
    if (mode === 'edit' && data.prompt) {
      if (hydrationSourceRef.current === data.prompt) return;
      const next = createPromptDraftFromPrompt(data.prompt);
      replaceState(next);
      setBaseline(next);
      hydrationSourceRef.current = data.prompt;
      return;
    }
    if (mode === 'create') {
      if (data.promptTemplate) {
        if (hydrationSourceRef.current === data.promptTemplate) return;
        const next = createPromptDraftFromPrompt(data.promptTemplate);
        replaceState(next);
        setBaseline(next);
        hydrationSourceRef.current = data.promptTemplate;
        return;
      }
      // No template yet — render an empty draft. Only hydrate empty once
      // (sentinel value `EMPTY_HYDRATION_TOKEN`) so flickering template
      // loading states don't churn the draft. Once a real template arrives
      // it replaces the empty seed via the branch above.
      if (hydrationSourceRef.current === EMPTY_HYDRATION_TOKEN) return;
      const empty = createEmptyPromptDraft();
      replaceState(empty);
      setBaseline(empty);
      hydrationSourceRef.current = EMPTY_HYDRATION_TOKEN;
    }
  }, [data.prompt, data.promptTemplate, mode, replaceState]);

  // Carry the project repository url onto the draft so the save action can
  // attach it to PromptSpecCreationRequest. Same depend-on-stable-callback
  // discipline as above.
  const draftRepositoryUrl = editor.state.draft.repositoryUrl;
  useEffect(() => {
    const url = data.activeProject?.repositoryUrl;
    if (!url) return;
    if (mode === 'create' && draftRepositoryUrl !== url) {
      setRepositoryUrl(url);
    } else if (!draftRepositoryUrl) {
      setRepositoryUrl(url);
    }
  }, [data.activeProject?.repositoryUrl, draftRepositoryUrl, mode, setRepositoryUrl]);

  const isEvaluationCapabilityEnabled =
    featureFlags.evals && Boolean(capabilities.data?.features?.includes('evals'));

  const formDraft = useMemo(
    () => toFormDraft(editor.state, toolConfigs),
    [editor.state, toolConfigs],
  );

  // Issue #185 — dirty signal. Compared against the post-hydration baseline.
  const isDirty = usePromptFormDirty({ current: editor.state, baseline });

  // Issue #182: surface an estimated input-token count next to the message
  // list while the user edits. The estimator runs client-side using the
  // generic cl100k_base tokenizer; the chip is hidden while the encoder is
  // loading so a "0 tokens" flash doesn't appear on first paint.
  const tokenEstimateInput = useMemo(
    () => ({
      messages: formDraft.request.messages.map((m) => ({
        role: String(m.role ?? ''),
        content: m.content ?? '',
      })),
      placeholders: formDraft.placeholders.list.map((p) => ({
        name: p.name ?? '',
        defaultValue: p.defaultValue ?? '',
      })),
      // Tool-schema overhead approximation: serialize the configured tool
      // entries (name + scenario metadata) and let the estimator count their
      // tokens. v1 doesn't introspect a JSON-Schema parameters block because
      // it isn't currently part of FormToolConfig.
      toolSchema: toolConfigs.length > 0 ? toolConfigs : undefined,
      startPattern: formDraft.placeholders.startPattern,
      endPattern: formDraft.placeholders.endPattern,
    }),
    [formDraft, toolConfigs],
  );
  const tokenEstimate = useTokenEstimate(tokenEstimateInput);
  const inputTokenEstimateLabel = useMemo(() => {
    if (tokenEstimate.tokens === null) return null;
    return `~${tokenEstimate.tokens.toLocaleString()} tokens`;
  }, [tokenEstimate.tokens]);

  const handleChangeDraft = useCallback(
    (next: PromptFormDraft) => {
      editor.replaceState({
        ...editor.state,
        draft: applyFormDraft(editor.state.draft, next),
      });
      setToolConfigs(next.toolConfigs);
    },
    [editor],
  );

  const handleToggleEvalEnabled = useCallback(
    (enabled: boolean) => {
      editor.setEvaluationEnabled(enabled);
    },
    [editor],
  );

  const formContext: PromptFormContext = useMemo(() => {
    const repositoryUrl =
      data.activeProject?.repositoryUrl ??
      data.prompt?.repositoryUrl ??
      'local repository';
    // Issue #184: prefer the release tag, otherwise the Git short SHA. Both
    // fields are server-derived (see PromptSpecApiView) and may be absent —
    // in which case the topbar falls back to the legacy "next will bump"
    // copy. Selection logic lives in `selectRevisionId` so it can be tested
    // independently of the React component.
    const revisionId = selectRevisionId(
      data.prompt as
        | (typeof data.prompt & { releaseTag?: string | null; headShortSha?: string | null })
        | null,
    );
    // Issue #188: compose the "View on GitHub" URL client-side from the
    // project's remote (project-level concern, can change), the spec's path,
    // and the head SHA. The backend no longer attaches a viewOnRemoteUrl to
    // the spec — see buildViewOnRemoteUrl for the gating rules. Empty in
    // create mode (no prompt loaded yet) and whenever the project's remote
    // is not a recognised GitHub URL.
    const specView = data.prompt as
      | (typeof data.prompt & {
          path?: string | null;
          headShortSha?: string | null;
          lifecycleState?: string | null;
        })
      | null;
    const viewOnRemoteUrl = buildViewOnRemoteUrl({
      projectRemoteUrl: data.activeProject?.repositoryUrl,
      specPath: specView?.path,
      headSha: specView?.headShortSha,
      lifecycleState: specView?.lifecycleState,
    });
    return {
      version: data.prompt?.version ?? FALLBACK_VERSION,
      revision: data.prompt?.revision ? `r${data.prompt.revision}` : FALLBACK_REVISION,
      repositoryUrl,
      branch: 'main',
      revisionId,
      viewOnRemoteUrl,
    };
  }, [data.activeProject?.repositoryUrl, data.prompt]);

  // Issue #185 — guarded navigation helper. If the form is dirty, defer the
  // navigation until the user picks Save / Discard / Cancel; otherwise run
  // immediately.
  const requestNavigation = useCallback(
    (perform: () => void) => {
      if (!isDirty) {
        perform();
        return;
      }
      pendingNavigationRef.current = perform;
      setDialogOpen(true);
    },
    [isDirty],
  );

  const handleCancel = useCallback(() => {
    requestNavigation(() => {
      if (mode === 'edit' && promptId) {
        navigate(`/prompts/${promptId}`);
        return;
      }
      navigate('/prompts');
    });
  }, [mode, navigate, promptId, requestNavigation]);

  const evaluationEnabledForPayload = isEvaluationCapabilityEnabled
    ? editor.state.evaluationEnabled
    : Boolean(editor.state.draft.extensions?.['x-evaluation']);

  const persistDraft = useCallback(async () => {
    flushSync(() => setValidationRequested(true));
    const result = await savePromptDraftAction({
      mode,
      createdPromptId,
      promptId: data.promptId,
      activeProjectId: data.activeProjectId,
      activeProjectRepositoryUrl: data.activeProject?.repositoryUrl,
      draft: editor.state.draft,
      evaluationEnabled: evaluationEnabledForPayload,
      validationHasErrors: false,
      createPrompt: data.createPrompt,
      updatePrompt: data.updatePrompt,
    });
    if (result.updatedPrompt && mode === 'edit') {
      const refreshed = createPromptDraftFromPrompt(result.updatedPrompt);
      editor.replaceState(refreshed);
      setBaseline(refreshed);
    }
    if (result.shouldRefreshPrompt) {
      await data.refreshPrompt();
    }
    if (result.nextCreatedPromptId !== createdPromptId) {
      setCreatedPromptId(result.nextCreatedPromptId);
    }
    // Issue #360 — kick off the prompt-execution SSE subscription once we
    // have a valid prompt id. The backend executes asynchronously after a
    // successful save; this surfaces the executing → executed → pushed (or
    // failed) progression in the response panel.
    if (result.toast.severity === 'success') {
      const trackedId =
        result.nextCreatedPromptId ?? data.promptId ?? createdPromptId ?? null;
      if (trackedId) {
        lastExecutionPromptIdRef.current = trackedId;
        executionSubscription.start(trackedId, {
          onExecuted: async () => {
            // Refetch the prompt so the streamed response renders in the
            // response panel below the form. Best-effort — the hook ignores
            // refetch failures so a hiccup doesn't trip the failed state.
            if (mode === 'edit') {
              await data.refreshPrompt();
            }
          },
        });
      }
    }
    return result;
  }, [
    createdPromptId,
    data,
    editor,
    evaluationEnabledForPayload,
    executionSubscription,
    mode,
  ]);

  const handleSaveDraft = useCallback(async () => {
    await persistDraft();
  }, [persistDraft]);

  // Issue #360 — Retry calls the dedicated backend endpoint and resubscribes
  // to the execution channel. Reuses the most recently-tracked prompt id
  // (captured by `persistDraft`).
  const handleRetryExecution = useCallback(async () => {
    const promptSpecId = lastExecutionPromptIdRef.current;
    if (!promptSpecId || isRetryingExecution) return;
    setIsRetryingExecution(true);
    try {
      await retryPromptExecution({ promptSpecId });
      executionSubscription.start(promptSpecId, {
        onExecuted: async () => {
          if (mode === 'edit') {
            await data.refreshPrompt();
          }
        },
      });
    } catch {
      // Leave the panel in `failed` so the user can retry again. The hook's
      // own error path will surface stream errors; the POST-level failure
      // surfaces below via the existing toast pipeline if we ever wire one.
    } finally {
      setIsRetryingExecution(false);
    }
  }, [data, executionSubscription, isRetryingExecution, mode]);

  // Issue #360 — auto-dismiss the `pushed` confirmation after 1.5s so it
  // behaves like a transient pill rather than a permanent banner.
  useEffect(() => {
    if (executionSubscription.state !== 'pushed') return undefined;
    const handle = window.setTimeout(() => executionSubscription.reset(), 1500);
    return () => window.clearTimeout(handle);
  }, [executionSubscription, executionSubscription.state]);

  const handleSubmit = useCallback(async () => {
    const saveResult = await persistDraft();
    if (saveResult.toast.severity === 'error') {
      return;
    }
    if (mode === 'create') {
      // Successful create — navigate to detail.
      const newId = saveResult.nextCreatedPromptId ?? createdPromptId;
      if (newId) {
        navigate(`/prompts/${newId}`);
      } else {
        navigate('/prompts');
      }
      return;
    }
    // Edit mode: chase save with release.
    setIsReleasing(true);
    try {
      const releaseResult = await releasePromptAction({
        mode,
        createdPromptId,
        promptId: data.promptId,
        releasePrompt: data.releasePrompt,
      });
      if (releaseResult.releasedPrompt && mode === 'edit') {
        const refreshed = createPromptDraftFromPrompt(releaseResult.releasedPrompt);
        editor.replaceState(refreshed);
        setBaseline(refreshed);
      }
      if (releaseResult.shouldRefreshPrompt) {
        await data.refreshPrompt();
      }
      if (releaseResult.nextCreatedPromptId !== createdPromptId) {
        setCreatedPromptId(releaseResult.nextCreatedPromptId);
      }
    } finally {
      setIsReleasing(false);
    }
  }, [createdPromptId, data, editor, mode, navigate, persistDraft]);

  const effectivePromptId = data.promptId ?? createdPromptId;

  const handleEditorRun = useCallback(async () => {
    if (!effectivePromptId || editorRunState === 'running') return;
    setEditorRunState('running');
    const startMs = Date.now();
    try {
      // Issue #183: send the current form-state draft as the request body so
      // the backend executes what the user sees in the editor, not the stored
      // YAML. The path id remains authoritative — the controller forces it
      // onto the spec and records the execution under the stored prompt id.
      //
      // Issue #140: forward the form's dirty flag as `draft`. The backend
      // uses this as the authoritative discriminator: a clean Run
      // (isDirty=false) records a MANUAL Execution; an unsaved-edit Run
      // (isDirty=true) is ephemeral. See PromptSpecController#executeStoredPrompt.
      const executeRequest = buildEditorRunRequest({
        draft: editor.state.draft,
        evaluationEnabled: evaluationEnabledForPayload,
        repositoryUrl: data.activeProject?.repositoryUrl,
        isDirty,
      });
      const result = await promptSpecs.executeStoredPrompt(effectivePromptId, executeRequest);
      const exec = result?.executions?.[0];
      const ms = Date.now() - startMs;
      const userMessagePh = (exec?.placeholders ?? []).find((ph) => ph.name === 'user_message');
      const lastUserMessage = [...(result?.request?.messages ?? [])]
        .reverse()
        .find((m) => m.role === 'user');
      setLastEditorRun({
        when: 'just now',
        kind: 'run',
        ms: exec?.latencyMs ?? ms,
        tin: exec?.tokensIn ?? exec?.response?.usage?.input_tokens ?? 0,
        tout: exec?.tokensOut ?? exec?.response?.usage?.output_tokens ?? 0,
        // Issue #182: USD cost surfaced on the result panel when the backend
        // can price the model. selectRunCost returns null for unknown models
        // so the chip is hidden rather than rendered as $0.00.
        cost: selectRunCost(exec),
        ok: typeof exec?.ok === 'boolean' ? exec.ok : exec?.response !== undefined,
        rev: formContext.revision,
        input:
          userMessagePh?.defaultValue ??
          (exec?.placeholders ?? [])[0]?.defaultValue ??
          lastUserMessage?.content,
        response: exec?.response?.content,
        error: exec?.error,
      });
    } catch {
      setLastEditorRun({
        when: 'just now',
        kind: 'run',
        ms: Date.now() - startMs,
        tin: 0,
        tout: 0,
        ok: false,
        rev: formContext.revision,
        error: 'execution failed',
      });
    } finally {
      setEditorRunState('idle');
    }
  }, [
    effectivePromptId,
    editorRunState,
    promptSpecs,
    formContext.revision,
    editor.state.draft,
    evaluationEnabledForPayload,
    data.activeProject?.repositoryUrl,
    isDirty,
  ]);

  // Issue #185 — `beforeunload`: prompt on tab close / reload while dirty.
  useEffect(() => {
    if (!isDirty) return;
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      // Older Chrome/Edge require the legacy returnValue setter.
      event.returnValue = '';
      return '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [isDirty]);

  // Issue #185 — in-app browser-back guard. While dirty, push a sentinel
  // history entry so the first `popstate` lands on our handler. We swallow
  // the back navigation, route the user through the dialog, and re-issue
  // `history.back()` if they confirm. If they cancel, the sentinel stays
  // in place so the next back press is still intercepted.
  //
  // Race avoidance (#241): the push is idempotent, and the cleanup does NOT
  // auto-`history.back()` to pop the sentinel. Auto-popping fires a
  // `popstate` event asynchronously; when `isDirty` flickers (e.g., the
  // default-template hydration arrives while the user is typing), this
  // effect tears down + re-mounts, and the deferred popstate from the
  // teardown's `history.back()` is caught by the *re-registered* listener,
  // spuriously opening `UnsavedChangesDialog` mid-edit. The sentinel is
  // explicitly popped via `pendingNavigationRef` on Save / Discard /
  // intentional back; the worst case otherwise is a single residual history
  // entry, which is harmless.
  useEffect(() => {
    if (!isDirty) return;
    const currentState = window.history.state as Record<string, unknown> | null;
    if (!currentState || currentState[POPSTATE_SENTINEL_KEY] !== true) {
      window.history.pushState({ [POPSTATE_SENTINEL_KEY]: true }, '');
    }
    const handler = () => {
      pendingNavigationRef.current = () => {
        // Step out of the form route. The sentinel entry was already
        // consumed by the popstate that just fired.
        window.history.back();
      };
      setDialogOpen(true);
    };
    window.addEventListener('popstate', handler);
    return () => {
      window.removeEventListener('popstate', handler);
    };
  }, [isDirty]);

  const handleDialogCancel = useCallback(() => {
    pendingNavigationRef.current = null;
    setDialogOpen(false);
  }, []);

  const handleDialogDiscard = useCallback(() => {
    if (baseline) {
      editor.replaceState(baseline);
    }
    const perform = pendingNavigationRef.current;
    pendingNavigationRef.current = null;
    setDialogOpen(false);
    if (perform) perform();
  }, [baseline, editor]);

  const handleDialogSave = useCallback(async () => {
    const result = await persistDraft();
    if (result.toast.severity === 'error') {
      // Save failed; keep the dialog open so the user can pick another path.
      return;
    }
    const perform = pendingNavigationRef.current;
    pendingNavigationRef.current = null;
    setDialogOpen(false);
    if (perform) perform();
  }, [persistDraft]);

  // Issue #187: bridge MessagesEditor's selection events into a ref so the
  // Insert handler can read the latest caret without subscribing to renders.
  const handleContentSelectionChange = useCallback(
    (selection: CaretSelection | null) => {
      caretSelectionRef.current = selection;
    },
    [],
  );

  // Auto-clear the no-caret hint after 4s so it doesn't linger as a permanent
  // banner. Re-runs whenever the hint value changes; the nonce ref above is
  // not used in the dep array because the hint string itself changes value
  // each time we set it (we append a zero-width nonce indirectly via state).
  useEffect(() => {
    if (!placeholderInsertHint) return undefined;
    const handle = window.setTimeout(() => setPlaceholderInsertHint(null), 4000);
    return () => window.clearTimeout(handle);
  }, [placeholderInsertHint]);

  const handleInsertPlaceholder = useCallback(
    (name: string) => {
      if (!name) return;
      const selection = caretSelectionRef.current;
      const startPattern = editor.state.draft.placeholders.startPattern ?? '{{';
      const endPattern = editor.state.draft.placeholders.endPattern ?? '}}';
      const token = buildPlaceholderToken(startPattern, name, endPattern);
      const result = insertPlaceholderAtCaret(
        editor.state.draft.request.messages,
        token,
        selection,
      );
      if (result.type !== 'inserted') {
        placeholderInsertHintNonce.current += 1;
        // Append a zero-width marker so identical messages still mutate state
        // and re-trigger the auto-clear effect on every click.
        setPlaceholderInsertHint(
          `${PLACEHOLDER_INSERT_NO_CARET_HINT}${'​'.repeat(placeholderInsertHintNonce.current % 5)}`,
        );
        return;
      }
      // Apply the next content immutably onto the draft. Mirrors the shape
      // used by applyFormDraft above so the existing reducer accepts it.
      const nextMessages = editor.state.draft.request.messages.map((m, index) =>
        index === result.messageIndex ? { ...m, content: result.nextContent } : m,
      );
      editor.replaceState({
        ...editor.state,
        draft: {
          ...editor.state.draft,
          request: {
            ...editor.state.draft.request,
            messages: nextMessages,
          },
        },
      });
      setPlaceholderInsertHint(null);
      // After React commits the new content, refocus the matching textarea
      // and place the caret after the inserted token. We locate it by its
      // aria-label, which MessagesEditor sets to "Message content {1-based}".
      const target = result.messageIndex;
      const caret = result.caretPosition;
      window.setTimeout(() => {
        const selector = `textarea[aria-label="Message content ${target + 1}"]`;
        const el = document.querySelector(selector) as HTMLTextAreaElement | null;
        if (!el) return;
        el.focus();
        try {
          el.setSelectionRange(caret, caret);
        } catch {
          // Some test environments don't implement setSelectionRange on
          // textareas — focusing is still useful.
        }
        caretSelectionRef.current = {
          messageIndex: target,
          selectionStart: caret,
          selectionEnd: caret,
        };
      }, 0);
    },
    [editor],
  );

  if (data.promptError) {
    return (
      <div
        role="alert"
        style={{
          margin: 32,
          padding: '12px 16px',
          background: 'color-mix(in oklch, var(--pl-fail) 8%, var(--pl-paper))',
          border: '1px solid color-mix(in oklch, var(--pl-fail) 30%, var(--pl-ink-200))',
          color: 'oklch(0.42 0.13 25)',
          borderRadius: 'var(--pl-r-md)',
          fontSize: 13.5,
        }}
      >
        Failed to load prompt. {data.promptError.message}
      </div>
    );
  }

  if ((mode === 'edit' && data.isPromptLoading) || (mode === 'create' && data.isPromptTemplateLoading)) {
    return (
      <div style={{ padding: 32, color: 'var(--pl-ink-600)', fontSize: 13.5 }}>
        Loading prompt…
      </div>
    );
  }

  // The shell is purposely loose about validation — `PromptFormPage`
  // re-runs `validateDraft` internally for inline display. The legacy
  // `validatePromptEditor` path stays available behind the scenes for the
  // existing actions which still expect it; `validationRequested` is
  // forwarded as a hint for cards that render request-deferred errors.
  void validationRequested;

  return (
    <>
      {executionSubscription.state !== 'idle' && (
        <div style={{ padding: '8px 24px 0' }}>
          <PromptExecutionStatusPanel
            state={executionSubscription.state}
            errorMessage={executionSubscription.errorMessage}
            onRetry={handleRetryExecution}
            isRetrying={isRetryingExecution}
          />
        </div>
      )}
      <PromptFormPage
        mode={mode}
        draft={formDraft}
        context={formContext}
        evalEnabled={editor.state.evaluationEnabled}
        evalsAvailable={isEvaluationCapabilityEnabled}
        isBusy={data.isSaving || isReleasing}
        isSaving={data.isSaving}
        onChangeDraft={handleChangeDraft}
        onToggleEvalEnabled={handleToggleEvalEnabled}
        onCancel={handleCancel}
        onSaveDraft={handleSaveDraft}
        onSubmit={handleSubmit}
        releaseFlowEnabled={featureFlags.releaseFlow}
        onEditorRun={effectivePromptId ? handleEditorRun : undefined}
        editorRunState={editorRunState}
        lastEditorRun={lastEditorRun}
        isDirty={isDirty}
        inputTokenEstimateLabel={inputTokenEstimateLabel}
        onContentSelectionChange={handleContentSelectionChange}
        onInsertPlaceholder={handleInsertPlaceholder}
        placeholderInsertHint={placeholderInsertHint}
      />
      <UnsavedChangesDialog
        open={dialogOpen}
        isSaving={data.isSaving}
        onSave={handleDialogSave}
        onDiscard={handleDialogDiscard}
        onCancel={handleDialogCancel}
      />
    </>
  );
};

export default PromptFormShell;
