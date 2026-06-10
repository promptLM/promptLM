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

import * as React from 'react';
import {
  Checkbox,
  FieldLabel,
  FormMono,
  GhostButton,
  NumberInput,
  Select,
  TextArea,
  TextInput,
} from './atoms';
import { Collapsible } from './Collapsible';
import type {
  EvaluationKind,
  FormEvaluation,
  FormMessage,
  FormParameters,
  FormPlaceholder,
  FormPlaceholdersConfig,
  FormRequest,
  FormToolConfig,
  MessageRole,
  PlaceholderType,
  PromptFormDraft,
  PromptFormErrors,
  ToolScenario,
} from './types';

const ROLE_DOT_COLOR: Record<MessageRole, string> = {
  system: 'oklch(0.50 0.10 270)',
  user: 'var(--pl-signal-deep)',
  assistant: 'oklch(0.45 0.13 155)',
  tool: 'oklch(0.50 0.13 70)',
};

// ───────────────────────── IdentityBlock ─────────────────────────

export interface IdentityBlockProps {
  draft: Pick<PromptFormDraft, 'name' | 'group' | 'description'>;
  errors: PromptFormErrors;
  mode: 'create' | 'edit';
  defaultOpen: boolean;
  onChange: (patch: Partial<Pick<PromptFormDraft, 'name' | 'group' | 'description'>>) => void;
}

export const IdentityBlock: React.FC<IdentityBlockProps> = ({
  draft,
  errors,
  mode,
  defaultOpen,
  onChange,
}) => (
  <Collapsible
    title="Identity"
    hint={mode === 'edit' ? `${draft.group}/${draft.name}` : 'name + group + description'}
    errorCount={errors.metadataCount}
    defaultOpen={defaultOpen}
    dense
    idPrefix="metadata-details"
    regionId="metadata-details-region"
  >
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 10 }}>
      <div>
        <FieldLabel
          required
          error={
            errors.metadata.name ? (
              <span data-testid="prompt-name-error">{errors.metadata.name}</span>
            ) : undefined
          }
        >
          Name
        </FieldLabel>
        <TextInput
          value={draft.name}
          onChange={(value) => onChange({ name: value })}
          error={errors.metadata.name}
          mono
          placeholder="e.g. doc-rag-answer"
          inputMode="text"
          testId="prompt-name-input"
        />
      </div>
      <div>
        <FieldLabel
          required
          error={
            errors.metadata.group ? (
              <span data-testid="prompt-group-error">{errors.metadata.group}</span>
            ) : undefined
          }
        >
          Group
        </FieldLabel>
        <TextInput
          value={draft.group}
          onChange={(value) => onChange({ group: value })}
          error={errors.metadata.group}
          mono
          placeholder="e.g. rag"
          inputMode="text"
          testId="prompt-group-input"
        />
      </div>
    </div>
    <FieldLabel required error={errors.metadata.description}>
      Description
    </FieldLabel>
    <TextArea
      value={draft.description}
      onChange={(value) => onChange({ description: value })}
      rows={2}
      error={errors.metadata.description}
      mono={false}
      testId="description-text"
    />
  </Collapsible>
);

// ───────────────────────── MessagesEditor ─────────────────────────

export interface MessagesEditorProps {
  messages: FormMessage[];
  placeholders: FormPlaceholdersConfig;
  errors: PromptFormErrors['messages'];
  itemErrors: PromptFormErrors['messageItems'];
  onChange: (next: FormMessage[]) => void;
  onContentSelectionChange?: (
    selection: { messageIndex: number; selectionStart: number; selectionEnd: number } | null,
  ) => void;
  /**
   * Optional estimated input-token label rendered next to the message count
   * (e.g. `~1,200 tokens`). When omitted the chip is hidden. Issue #182.
   */
  estimateLabel?: string | null;
}

const MESSAGE_ROLE_OPTIONS = [
  { value: 'system', label: 'system' },
  { value: 'user', label: 'user' },
  { value: 'assistant', label: 'assistant' },
  { value: 'tool', label: 'tool' },
] as const;

export const MessagesEditor: React.FC<MessagesEditorProps> = ({
  messages,
  placeholders,
  errors,
  itemErrors,
  onChange,
  onContentSelectionChange,
  estimateLabel,
}) => {
  const update = (i: number, patch: Partial<FormMessage>) => {
    const next = [...messages];
    next[i] = { ...next[i], ...patch };
    onChange(next);
  };
  const remove = (i: number) => onChange(messages.filter((_, idx) => idx !== i));
  const add = (role: MessageRole) =>
    onChange([
      ...messages,
      { role, content: '', name: role === 'tool' ? '' : undefined },
    ]);

  const phNames = placeholders.list.map((p) => p.name).filter(Boolean);

  const emitSelection = (messageIndex: number, target: HTMLTextAreaElement) => {
    if (!onContentSelectionChange) return;
    const selectionStart = target.selectionStart ?? 0;
    const selectionEnd = target.selectionEnd ?? selectionStart;
    onContentSelectionChange({ messageIndex, selectionStart, selectionEnd });
  };

  return (
    <div>
      <div
        style={{
          display: 'flex',
          alignItems: 'baseline',
          gap: 12,
          marginBottom: 12,
          paddingBottom: 10,
          borderBottom: '1px solid var(--pl-ink-200)',
          flexWrap: 'wrap',
        }}
      >
        <h3
          style={{
            margin: 0,
            fontFamily: 'var(--pl-display)',
            fontSize: 16,
            fontWeight: 500,
            color: 'var(--pl-ink-900)',
            letterSpacing: '-0.005em',
          }}
        >
          Messages
        </h3>
        <FormMono size={10.5} color="var(--pl-ink-500)">
          {messages.length} {messages.length === 1 ? 'message' : 'messages'}
        </FormMono>
        {estimateLabel ? (
          <FormMono
            size={10.5}
            color="var(--pl-ink-500)"
            data-testid="prompt-editor-token-estimate"
            style={{ fontVariantNumeric: 'tabular-nums' }}
          >
            · {estimateLabel}
          </FormMono>
        ) : null}
        {errors.general ? (
          <FormMono size={10.5} color="oklch(0.50 0.15 25)">
            ! {errors.general}
          </FormMono>
        ) : null}
        <div style={{ flex: 1 }} />
        <FormMono size={10} color="var(--pl-ink-500)">
          + insert
        </FormMono>
        <GhostButton mini onClick={() => add('system')}>
          system
        </GhostButton>
        <GhostButton mini onClick={() => add('user')} testId="user-prompt-button">
          user
        </GhostButton>
        <GhostButton mini onClick={() => add('assistant')}>
          assistant
        </GhostButton>
        <GhostButton mini onClick={() => add('tool')}>
          tool
        </GhostButton>
      </div>

      {phNames.length > 0 ? (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            flexWrap: 'wrap',
            padding: '8px 12px',
            marginBottom: 12,
            background: 'var(--pl-canvas)',
            borderRadius: 5,
            border: '1px solid var(--pl-ink-200)',
          }}
        >
          <FormMono
            size={10}
            color="var(--pl-ink-500)"
            style={{ letterSpacing: '0.10em', textTransform: 'uppercase' }}
          >
            placeholders
          </FormMono>
          {phNames.map((name) => (
            <span
              key={name}
              style={{
                padding: '2px 7px',
                borderRadius: 3,
                background: 'oklch(0.95 0.06 230)',
                color: 'var(--pl-signal-deep)',
                fontFamily: 'var(--pl-mono)',
                fontSize: 11.5,
              }}
            >
              {`{{${name}}}`}
            </span>
          ))}
        </div>
      ) : null}

      <div style={{ display: 'grid', gap: 10 }} data-testid="prompt-messages">
        {messages.map((m, i) => {
          const e = itemErrors[i] ?? {};
          const isLastUser = m.role === 'user' && i === messages.length - 1;
          return (
            <div
              key={i}
              style={{
                border: '1px solid var(--pl-ink-200)',
                borderRadius: 5,
                background: 'var(--pl-paper)',
                overflow: 'hidden',
              }}
            >
              <div
                style={{
                  padding: '6px 10px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  borderBottom: '1px solid var(--pl-ink-200)',
                  background: 'var(--pl-canvas)',
                }}
              >
                <Select
                  compact
                  value={m.role}
                  onChange={(value) =>
                    update(i, {
                      role: value as MessageRole,
                      name: value === 'tool' ? m.name ?? '' : undefined,
                    })
                  }
                  options={MESSAGE_ROLE_OPTIONS}
                />
                <span
                  style={{
                    width: 8,
                    height: 8,
                    borderRadius: 999,
                    background: ROLE_DOT_COLOR[m.role],
                  }}
                />
                {m.role === 'tool' ? (
                  <input
                    type="text"
                    value={m.name ?? ''}
                    onChange={(event) => update(i, { name: event.target.value })}
                    placeholder="tool name"
                    className={`pl-form-input ${e.name ? 'pl-form-err' : ''}`}
                    style={{
                      width: 180,
                      padding: '4px 8px',
                      fontFamily: 'var(--pl-mono)',
                      fontSize: 12,
                      color: 'var(--pl-ink-900)',
                      background: 'var(--pl-paper)',
                      borderWidth: 1,
                      borderStyle: 'solid',
                      borderColor: e.name ? 'oklch(0.65 0.15 25)' : 'var(--pl-ink-200)',
                      borderRadius: 4,
                      outline: 'none',
                    }}
                  />
                ) : null}
                <div style={{ flex: 1 }} />
                <FormMono size={10} color="var(--pl-ink-400)">
                  #{i + 1}
                </FormMono>
                {messages.length > 1 ? (
                  <GhostButton mini danger onClick={() => remove(i)}>
                    ×
                  </GhostButton>
                ) : null}
              </div>
              <div style={{ padding: 8 }}>
                <TextArea
                  value={m.content}
                  onChange={(value) => update(i, { content: value })}
                  rows={Math.min(12, Math.max(3, m.content.split('\n').length + 1))}
                  error={e.content}
                  flush
                  ariaLabel={`Message content ${i + 1}`}
                  testId={isLastUser ? 'prompt-text' : undefined}
                  onSelect={(event) => emitSelection(i, event.currentTarget)}
                  onClick={(event) => emitSelection(i, event.currentTarget)}
                  onKeyUp={(event) => emitSelection(i, event.currentTarget)}
                  onFocus={(event) => emitSelection(i, event.currentTarget)}
                  // Keep the last caret selection across blur so the rail Insert button
                  // (mouse or keyboard) can read it. The insert handler validates the
                  // message index, so a stale-but-bounded ref is harmless. See #228 / #217.
                />
                {e.content ? (
                  <FormMono size={10} color="oklch(0.50 0.15 25)">
                    ! {e.content}
                  </FormMono>
                ) : null}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

// ───────────────────────── RailModel ─────────────────────────

const VENDOR_OPTIONS = [
  { value: '', label: '— select —' },
  { value: 'anthropic', label: 'Anthropic' },
  { value: 'openai', label: 'OpenAI' },
  { value: 'google', label: 'Google' },
  { value: 'azure', label: 'Azure OpenAI' },
  { value: 'custom', label: 'Custom' },
] as const;

const PARAM_FIELDS: Array<{
  key: keyof FormParameters;
  label: string;
  step: number;
  min?: number;
  max?: number;
  testId: string;
}> = [
  { key: 'temperature', label: 'Temp', step: 0.1, min: 0, max: 2, testId: 'request-temperature-input' },
  { key: 'topP', label: 'Top P', step: 0.05, min: 0, max: 1, testId: 'request-top-p-input' },
  { key: 'maxTokens', label: 'Max tok', step: 64, min: 1, testId: 'request-max-tokens-input' },
  {
    key: 'frequencyPenalty',
    label: 'Freq pen',
    step: 0.1,
    min: -2,
    max: 2,
    testId: 'request-frequency-penalty-input',
  },
  {
    key: 'presencePenalty',
    label: 'Pres pen',
    step: 0.1,
    min: -2,
    max: 2,
    testId: 'request-presence-penalty-input',
  },
];

export interface RailModelProps {
  request: FormRequest;
  errors: PromptFormErrors['model'];
  paramErrors: PromptFormErrors['params'];
  defaultOpen: boolean;
  onChange: (patch: Partial<FormRequest>) => void;
}

export const RailModel: React.FC<RailModelProps> = ({
  request,
  errors,
  paramErrors,
  defaultOpen,
  onChange,
}) => {
  const errCount = Object.keys(errors).length + Object.keys(paramErrors).length;
  // Bug-fix from playbook §Notes: render the placeholder vendor option only
  // when the current value is empty.
  const vendorOptions = request.vendor
    ? VENDOR_OPTIONS.filter((opt) => opt.value !== '')
    : VENDOR_OPTIONS;

  return (
    <Collapsible
      title="Model"
      hint={`${request.vendor || '?'} · ${request.model || '?'}`}
      errorCount={errCount}
      defaultOpen={defaultOpen}
      dense
      idPrefix="rail-model"
    >
      <div style={{ display: 'grid', gap: 10 }}>
        <div>
          <FieldLabel required error={errors.vendor}>
            Vendor
          </FieldLabel>
          <Select
            compact
            value={request.vendor}
            onChange={(value) => onChange({ vendor: value })}
            options={vendorOptions}
            error={errors.vendor}
            testId="request-vendor-select"
          />
        </div>
        <div>
          <FieldLabel required error={errors.model}>
            Model
          </FieldLabel>
          <TextInput
            compact
            mono
            value={request.model}
            onChange={(value) => onChange({ model: value })}
            error={errors.model}
            placeholder="claude-sonnet-4-5"
            testId="request-model-select"
          />
        </div>
        <div>
          <FieldLabel hint="optional">Snapshot</FieldLabel>
          <TextInput
            compact
            mono
            value={request.modelSnapshot}
            onChange={(value) => onChange({ modelSnapshot: value })}
            placeholder="2025-04-14"
            testId="request-model-snapshot-input"
          />
        </div>

        <div
          style={{
            marginTop: 4,
            paddingTop: 10,
            borderTop: '1px dashed var(--pl-ink-200)',
          }}
        >
          <FormMono
            size={10}
            color="var(--pl-ink-500)"
            style={{
              letterSpacing: '0.10em',
              textTransform: 'uppercase',
              display: 'block',
              marginBottom: 8,
            }}
          >
            Parameters
          </FormMono>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
            {PARAM_FIELDS.map((p) => (
              <div key={p.key}>
                <FieldLabel error={paramErrors[p.key]}>{p.label}</FieldLabel>
                <NumberInput
                  value={request.parameters[p.key]}
                  onChange={(value) =>
                    onChange({ parameters: { ...request.parameters, [p.key]: value } })
                  }
                  step={p.step}
                  min={p.min}
                  max={p.max}
                  error={paramErrors[p.key]}
                  testId={p.testId}
                />
              </div>
            ))}
          </div>
        </div>
      </div>
    </Collapsible>
  );
};

// ───────────────────────── RailPlaceholders ─────────────────────────

const PLACEHOLDER_TYPE_OPTIONS = [
  { value: 'string', label: 'str' },
  { value: 'number', label: 'num' },
  { value: 'boolean', label: 'bool' },
  { value: 'json', label: 'json' },
] as const;

export interface RailPlaceholdersProps {
  placeholders: FormPlaceholdersConfig;
  errors: PromptFormErrors['placeholders'];
  itemErrors: PromptFormErrors['placeholderItems'];
  defaultOpen: boolean;
  onChange: (patch: Partial<FormPlaceholdersConfig>) => void;
  /**
   * Issue #187: clicking the per-row Insert affordance asks the host to insert
   * the placeholder token at the editor caret. Optional — when absent, the
   * row renders without an Insert button (back-compat with v1 callers).
   */
  onInsertPlaceholder?: (name: string) => void;
}

export const RailPlaceholders: React.FC<RailPlaceholdersProps> = ({
  placeholders,
  errors,
  itemErrors,
  defaultOpen,
  onChange,
  onInsertPlaceholder,
}) => {
  const list = placeholders.list;
  const errCount =
    Object.keys(errors).length + itemErrors.filter((x) => Object.keys(x).length > 0).length;
  const update = (i: number, patch: Partial<FormPlaceholder>) => {
    const next = [...list];
    next[i] = { ...next[i], ...patch };
    onChange({ list: next });
  };
  const remove = (i: number) => onChange({ list: list.filter((_, idx) => idx !== i) });
  const add = () =>
    onChange({
      list: [...list, { name: '', type: 'string', required: false, description: '' }],
    });

  return (
    <Collapsible
      title="Placeholders"
      hint={`${list.length}`}
      errorCount={errCount}
      defaultOpen={defaultOpen}
      dense
      idPrefix="placeholder-list"
      action={
        <GhostButton mini onClick={add} testId="placeholder-add-button">
          + Add
        </GhostButton>
      }
    >
      <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
        <div style={{ flex: 1 }}>
          <FieldLabel error={errors.startPattern}>Start</FieldLabel>
          <TextInput
            compact
            mono
            value={placeholders.startPattern}
            onChange={(value) => onChange({ startPattern: value })}
            error={errors.startPattern}
            testId="placeholder-open-sequence-input"
          />
        </div>
        <div style={{ flex: 1 }}>
          <FieldLabel error={errors.endPattern}>End</FieldLabel>
          <TextInput
            compact
            mono
            value={placeholders.endPattern}
            onChange={(value) => onChange({ endPattern: value })}
            error={errors.endPattern}
            testId="placeholder-close-sequence-input"
          />
        </div>
      </div>

      {list.length === 0 ? (
        <div
          style={{
            padding: 12,
            fontSize: 12,
            color: 'var(--pl-ink-500)',
            border: '1px dashed var(--pl-ink-300)',
            borderRadius: 4,
            textAlign: 'center',
          }}
        >
          No placeholders.
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 6 }}>
          {list.map((ph, i) => {
            const e = itemErrors[i] ?? {};
            const rowTestId = ph.name
              ? `placeholder-row-${ph.name}`
              : `placeholder-row-index-${i}`;
            const valueTestId = ph.name
              ? `placeholder-value-textarea-${ph.name}-0`
              : `placeholder-value-textarea-index-${i}`;
            return (
              <div
                key={i}
                data-testid={rowTestId}
                style={{
                  border: '1px solid var(--pl-ink-200)',
                  borderRadius: 4,
                  padding: 8,
                  background: 'var(--pl-canvas)',
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    gap: 6,
                    alignItems: 'center',
                    marginBottom: 6,
                  }}
                >
                  <TextInput
                    compact
                    mono
                    value={ph.name}
                    onChange={(value) => update(i, { name: value })}
                    error={e.name}
                    placeholder="name"
                    testId={`placeholder-name-input-${i}`}
                  />
                  <Select
                    compact
                    value={ph.type}
                    onChange={(value) => update(i, { type: value as PlaceholderType })}
                    options={PLACEHOLDER_TYPE_OPTIONS}
                  />
                  <Checkbox
                    size={11}
                    checked={ph.required}
                    onChange={(checked) => update(i, { required: checked })}
                    label="req"
                  />
                  {onInsertPlaceholder ? (
                    <GhostButton
                      mini
                      // Preserve the focused message textarea's caret across
                      // the click — without preventDefault on mousedown the
                      // textarea blurs first, which clears the selection ref
                      // that the insert handler relies on. See #217.
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => onInsertPlaceholder(ph.name)}
                      disabled={!ph.name}
                      testId={
                        ph.name
                          ? `placeholder-insert-button-${ph.name}`
                          : `placeholder-insert-button-index-${i}`
                      }
                    >
                      Insert
                    </GhostButton>
                  ) : null}
                  <GhostButton mini danger onClick={() => remove(i)}>
                    ×
                  </GhostButton>
                </div>
                <TextInput
                  compact
                  value={ph.description}
                  onChange={(value) => update(i, { description: value })}
                  placeholder="description"
                  testId={valueTestId}
                />
                {e.name ? (
                  <FormMono
                    size={10}
                    color="oklch(0.50 0.15 25)"
                    style={{ display: 'block', marginTop: 4 }}
                  >
                    ! {e.name}
                  </FormMono>
                ) : null}
              </div>
            );
          })}
        </div>
      )}
    </Collapsible>
  );
};

// ───────────────────────── RailTools ─────────────────────────

const TOOL_SCENARIO_OPTIONS = [
  { value: 'happy-path', label: 'happy-path' },
  { value: 'empty', label: 'empty' },
  { value: 'error', label: 'error' },
  { value: 'timeout', label: 'timeout' },
  { value: 'malformed', label: 'malformed' },
] as const;

export interface RailToolsProps {
  configs: FormToolConfig[];
  itemErrors: PromptFormErrors['toolItems'];
  defaultOpen: boolean;
  onChange: (next: FormToolConfig[]) => void;
}

export const RailTools: React.FC<RailToolsProps> = ({
  configs,
  itemErrors,
  defaultOpen,
  onChange,
}) => {
  const errCount = itemErrors.filter((x) => Object.keys(x).length > 0).length;
  const update = (i: number, patch: Partial<FormToolConfig>) => {
    const next = [...configs];
    next[i] = { ...next[i], ...patch };
    onChange(next);
  };
  const remove = (i: number) => onChange(configs.filter((_, idx) => idx !== i));
  const add = () =>
    onChange([
      ...configs,
      { name: '', scenario: 'happy-path', notes: '', mockResponse: '' },
    ]);

  return (
    <Collapsible
      title="MCP tool mocks"
      hint={`${configs.length}`}
      errorCount={errCount}
      defaultOpen={defaultOpen}
      dense
      idPrefix="rail-tools"
      action={
        <GhostButton mini onClick={add} testId="add-tool-button">
          + Add
        </GhostButton>
      }
    >
      {configs.length === 0 ? (
        <div
          style={{
            padding: 12,
            fontSize: 12,
            color: 'var(--pl-ink-500)',
            border: '1px dashed var(--pl-ink-300)',
            borderRadius: 4,
            textAlign: 'center',
          }}
        >
          No mocks.
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 8 }}>
          {configs.map((t, i) => {
            const e = itemErrors[i] ?? {};
            return (
              <div
                key={i}
                style={{
                  border: '1px solid var(--pl-ink-200)',
                  borderRadius: 4,
                  padding: 8,
                  background: 'var(--pl-canvas)',
                }}
              >
                <div style={{ display: 'flex', gap: 6, marginBottom: 6 }}>
                  <TextInput
                    compact
                    mono
                    value={t.name}
                    onChange={(value) => update(i, { name: value })}
                    error={e.name}
                    placeholder="tool name"
                    testId={`tool-name-input-${i}`}
                  />
                  <Select
                    compact
                    value={t.scenario}
                    onChange={(value) => update(i, { scenario: value as ToolScenario })}
                    options={TOOL_SCENARIO_OPTIONS}
                    error={e.scenario}
                  />
                  <GhostButton mini danger onClick={() => remove(i)}>
                    ×
                  </GhostButton>
                </div>
                <TextInput
                  compact
                  value={t.notes}
                  onChange={(value) => update(i, { notes: value })}
                  error={e.notes}
                  placeholder="notes"
                  testId={`tool-description-input-${i}`}
                />
                <div style={{ marginTop: 6 }}>
                  <TextArea
                    value={t.mockResponse}
                    onChange={(value) => update(i, { mockResponse: value })}
                    rows={2}
                    error={e.mockResponse}
                  />
                </div>
              </div>
            );
          })}
        </div>
      )}
    </Collapsible>
  );
};

// ───────────────────────── RailEvals ─────────────────────────

const EVAL_TYPE_OPTIONS = [
  { value: 'llm-judge', label: 'llm-judge' },
  { value: 'rubric', label: 'rubric' },
  { value: 'exact', label: 'exact' },
  { value: 'regex', label: 'regex' },
  { value: 'custom', label: 'custom' },
] as const;

export interface RailEvalsProps {
  evaluations: FormEvaluation[];
  evalEnabled: boolean;
  errors: PromptFormErrors['evals'];
  itemErrors: PromptFormErrors['evalItems'];
  defaultOpen: boolean;
  onToggleEnabled: (checked: boolean) => void;
  onChange: (next: FormEvaluation[]) => void;
}

export const RailEvals: React.FC<RailEvalsProps> = ({
  evaluations,
  evalEnabled,
  errors,
  itemErrors,
  defaultOpen,
  onToggleEnabled,
  onChange,
}) => {
  const errCount =
    (evalEnabled ? Object.keys(errors).length : 0) +
    itemErrors.filter((x) => Object.keys(x).length > 0).length;
  const update = (i: number, patch: Partial<FormEvaluation>) => {
    const next = [...evaluations];
    next[i] = { ...next[i], ...patch };
    onChange(next);
  };
  const remove = (i: number) => onChange(evaluations.filter((_, idx) => idx !== i));
  const add = () =>
    onChange([
      ...evaluations,
      { evaluator: '', type: 'llm-judge', description: '' },
    ]);

  return (
    <Collapsible
      title={
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          Evaluation plan
          <span
            style={{
              padding: '1px 6px',
              background: 'oklch(0.94 0.05 70)',
              color: 'oklch(0.45 0.13 70)',
              fontFamily: 'var(--pl-mono)',
              fontSize: 9.5,
              fontWeight: 500,
              letterSpacing: '0.10em',
              textTransform: 'uppercase',
              borderRadius: 3,
            }}
          >
            Pro · soon
          </span>
        </span>
      }
      hint={evalEnabled ? `${evaluations.length}` : 'disabled'}
      errorCount={errCount}
      defaultOpen={defaultOpen}
      dense
      idPrefix="rail-evals"
      action={
        <Checkbox
          size={11}
          checked={evalEnabled}
          onChange={onToggleEnabled}
          label="enable"
          testId="add-evaluation-button"
        />
      }
    >
      {!evalEnabled ? (
        <div style={{ padding: 12, fontSize: 12, color: 'var(--pl-ink-500)' }}>
          Toggle <FormMono size={11}>enable</FormMono> to define evaluators.
        </div>
      ) : (
        <>
          {errors.general ? (
            <FormMono
              size={10.5}
              color="oklch(0.50 0.15 25)"
              style={{ display: 'block', marginBottom: 8 }}
            >
              ! {errors.general}
            </FormMono>
          ) : null}
          <div style={{ display: 'grid', gap: 6 }}>
            {evaluations.map((ev, i) => {
              const e = itemErrors[i] ?? {};
              return (
                <div
                  key={i}
                  style={{
                    border: '1px solid var(--pl-ink-200)',
                    borderRadius: 4,
                    padding: 8,
                    background: 'var(--pl-canvas)',
                  }}
                >
                  <div style={{ display: 'flex', gap: 6, marginBottom: 6 }}>
                    <TextInput
                      compact
                      mono
                      value={ev.evaluator}
                      onChange={(value) => update(i, { evaluator: value })}
                      error={e.evaluator}
                      placeholder="evaluator"
                      testId={`evaluation-evaluator-input-${i}`}
                    />
                    <Select
                      compact
                      value={ev.type}
                      onChange={(value) => update(i, { type: value as EvaluationKind })}
                      options={EVAL_TYPE_OPTIONS}
                      error={e.type}
                      testId={`evaluation-type-input-${i}`}
                    />
                    <GhostButton mini danger onClick={() => remove(i)}>
                      ×
                    </GhostButton>
                  </div>
                  <TextInput
                    compact
                    value={ev.description}
                    onChange={(value) => update(i, { description: value })}
                    error={e.description}
                    placeholder="description"
                    testId={`evaluation-description-input-${i}`}
                  />
                </div>
              );
            })}
            <GhostButton mini onClick={add}>
              + Add evaluator
            </GhostButton>
          </div>
        </>
      )}
    </Collapsible>
  );
};

// ───────────────────────── SpecFileFooter ─────────────────────────

export interface SpecFileFooterProps {
  group: string;
  name: string;
}

export const SpecFileFooter: React.FC<SpecFileFooterProps> = ({ group, name }) => (
  <div
    style={{
      marginTop: 16,
      padding: '10px 12px',
      background: 'var(--pl-paper)',
      border: '1px solid var(--pl-ink-200)',
      borderRadius: 5,
    }}
  >
    <FormMono
      size={10}
      color="var(--pl-ink-500)"
      style={{
        letterSpacing: '0.10em',
        textTransform: 'uppercase',
        display: 'block',
        marginBottom: 4,
      }}
    >
      spec file
    </FormMono>
    <FormMono size={11} color="var(--pl-ink-800)">
      prompts/{group || '<group>'}/{name || '<name>'}.toml
    </FormMono>
  </div>
);
