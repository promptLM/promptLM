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

import { describe, expect, it, vi } from 'vitest';

import type { PromptSpec } from '@promptlm/api-client';
import type { PromptEditorExecution } from '@promptlm/ui';
import type { PromptDraftInput } from '@/api/promptPayloads';

import {
  mergeExecutions,
  pickSelectedExecution,
  releasePromptAction,
  savePromptDraftAction,
} from '../editorActions';

const buildDraft = (): PromptDraftInput => ({
  name: 'support-prompt',
  group: 'support',
  description: 'Assist support agents',
  placeholders: {
    startPattern: '{{',
    endPattern: '}}',
    list: [{ name: 'customer_name', value: 'Taylor' }],
    defaults: { customer_name: 'Taylor' },
  },
  request: {
    type: 'chat/completion',
    vendor: 'openai',
    model: 'gpt-4o',
    messages: [
      { role: 'system', content: 'You are a helpful assistant.' },
      { role: 'user', content: 'Help the customer.' },
    ],
  },
  evaluations: [],
});

const buildPrompt = (id: string, state: 'requested' | 'released' = 'released'): PromptSpec =>
  ({
    id,
    name: 'support-prompt',
    group: 'support',
    description: 'Assist support agents',
    request: {
      type: 'chat/completion',
      vendor: 'openai',
      model: 'gpt-4o',
      messages: [
        { role: 'SYSTEM', content: 'You are a helpful assistant.' },
        { role: 'USER', content: 'Help the customer.' },
      ],
    },
    placeholders: {
      startPattern: '{{',
      endPattern: '}}',
      list: [{ name: 'customer_name', defaultValue: 'Taylor' }],
      defaults: { customer_name: 'Taylor' },
    },
    extensions: {
      'x-promptlm': {
        release: {
          state,
          mode: state === 'requested' ? 'pr_two_phase' : 'direct',
          version: '1.0.0',
          tag: 'support-prompt-v1.0.0',
          branch: state === 'requested' ? 'release/support-prompt-1.0.0' : 'main',
          prNumber: state === 'requested' ? 11 : undefined,
          prUrl: state === 'requested' ? 'https://github.com/promptLM/promptlm-app/pull/11' : undefined,
          existing: false,
        },
      },
    },
  }) as PromptSpec;

describe('savePromptDraftAction', () => {
  it('returns an error toast when no active project is selected', async () => {
    const createPrompt = vi.fn();
    const updatePrompt = vi.fn();

    const result = await savePromptDraftAction({
      mode: 'create',
      createdPromptId: null,
      promptId: null,
      activeProjectId: null,
      activeProjectRepositoryUrl: null,
      draft: buildDraft(),
      evaluationEnabled: false,
      validationHasErrors: false,
      createPrompt,
      updatePrompt,
    });

    expect(result.toast).toEqual({
      severity: 'error',
      message: 'Select an active project before saving.',
    });
    expect(createPrompt).not.toHaveBeenCalled();
    expect(updatePrompt).not.toHaveBeenCalled();
  });

  it('returns an error toast when validation fails', async () => {
    const createPrompt = vi.fn();
    const updatePrompt = vi.fn();

    const result = await savePromptDraftAction({
      mode: 'create',
      createdPromptId: null,
      promptId: null,
      activeProjectId: 'project-1',
      activeProjectRepositoryUrl: 'https://example.com/repo',
      draft: buildDraft(),
      evaluationEnabled: false,
      validationHasErrors: true,
      createPrompt,
      updatePrompt,
    });

    expect(result.toast).toEqual({
      severity: 'error',
      message: 'Resolve validation errors before saving.',
    });
    expect(createPrompt).not.toHaveBeenCalled();
    expect(updatePrompt).not.toHaveBeenCalled();
  });

  it('creates a prompt in create mode before an id exists', async () => {
    const createPrompt = vi.fn().mockResolvedValue(buildPrompt('prompt-1'));
    const updatePrompt = vi.fn();

    const result = await savePromptDraftAction({
      mode: 'create',
      createdPromptId: null,
      promptId: null,
      activeProjectId: 'project-1',
      activeProjectRepositoryUrl: 'https://example.com/repo',
      draft: buildDraft(),
      evaluationEnabled: true,
      validationHasErrors: false,
      createPrompt,
      updatePrompt,
    });

    expect(createPrompt).toHaveBeenCalledTimes(1);
    expect(updatePrompt).not.toHaveBeenCalled();
    expect(result.nextCreatedPromptId).toBe('prompt-1');
    expect(result.toast).toEqual({
      severity: 'success',
      message: 'Saved. Running prompt…',
    });
  });

  it('updates a draft in create mode once an id exists', async () => {
    const createPrompt = vi.fn();
    const updatePrompt = vi.fn().mockResolvedValue(buildPrompt('prompt-1'));

    const result = await savePromptDraftAction({
      mode: 'create',
      createdPromptId: 'prompt-1',
      promptId: null,
      activeProjectId: 'project-1',
      activeProjectRepositoryUrl: 'https://example.com/repo',
      draft: buildDraft(),
      evaluationEnabled: false,
      validationHasErrors: false,
      createPrompt,
      updatePrompt,
    });

    expect(updatePrompt).toHaveBeenCalledWith('prompt-1', expect.any(Object));
    expect(createPrompt).not.toHaveBeenCalled();
    expect(result.nextCreatedPromptId).toBe('prompt-1');
    expect(result.toast.message).toBe('Saved. Running prompt…');
  });

  it('updates and marks refresh in edit mode', async () => {
    const createPrompt = vi.fn();
    const updated = buildPrompt('prompt-2');
    const updatePrompt = vi.fn().mockResolvedValue(updated);

    const result = await savePromptDraftAction({
      mode: 'edit',
      createdPromptId: null,
      promptId: 'prompt-2',
      activeProjectId: 'project-1',
      activeProjectRepositoryUrl: 'https://example.com/repo',
      draft: buildDraft(),
      evaluationEnabled: false,
      validationHasErrors: false,
      createPrompt,
      updatePrompt,
    });

    expect(result.updatedPrompt).toEqual(updated);
    expect(result.shouldRefreshPrompt).toBe(true);
    expect(result.toast.message).toBe('Saved. Running prompt…');
  });

  it('returns display errors from failing mutations', async () => {
    const createPrompt = vi.fn().mockRejectedValue(new Error('save failed'));
    const updatePrompt = vi.fn();

    const result = await savePromptDraftAction({
      mode: 'create',
      createdPromptId: null,
      promptId: null,
      activeProjectId: 'project-1',
      activeProjectRepositoryUrl: 'https://example.com/repo',
      draft: buildDraft(),
      evaluationEnabled: false,
      validationHasErrors: false,
      createPrompt,
      updatePrompt,
    });

    expect(result.toast).toEqual({
      severity: 'error',
      message: 'save failed',
    });
  });

  it('persists raw placeholder tokens in saved message content', async () => {
    const createPrompt = vi.fn().mockResolvedValue(buildPrompt('prompt-raw-token'));
    const updatePrompt = vi.fn();
    const draft = buildDraft();
    draft.placeholders = {
      startPattern: '[[',
      endPattern: ']]',
      list: [{ name: 'number_one', value: '1' }],
      defaults: { number_one: '1' },
    };
    draft.request.messages = [
      { role: 'user', content: 'Number one [[number_one]] equals?' },
    ];

    await savePromptDraftAction({
      mode: 'create',
      createdPromptId: null,
      promptId: null,
      activeProjectId: 'project-1',
      activeProjectRepositoryUrl: 'https://example.com/repo',
      draft,
      evaluationEnabled: false,
      validationHasErrors: false,
      createPrompt,
      updatePrompt,
    });

    expect(createPrompt).toHaveBeenCalledTimes(1);
    const payload = createPrompt.mock.calls[0][0];
    expect(payload.request?.messages).toEqual([
      {
        role: 'USER',
        content: 'Number one [[number_one]] equals?',
        name: undefined,
      },
    ]);
  });
});

describe('releasePromptAction', () => {
  it('no-ops when no releasable prompt id exists', async () => {
    const releasePrompt = vi.fn();

    const result = await releasePromptAction({
      mode: 'create',
      createdPromptId: null,
      promptId: null,
      releasePrompt,
    });

    expect(result.toast).toBeNull();
    expect(releasePrompt).not.toHaveBeenCalled();
  });

  it('releases create-mode prompts and returns publish messaging', async () => {
    const releasePrompt = vi.fn().mockResolvedValue(buildPrompt('prompt-10'));

    const result = await releasePromptAction({
      mode: 'create',
      createdPromptId: 'prompt-10',
      promptId: null,
      releasePrompt,
    });

    expect(result.nextCreatedPromptId).toBe('prompt-10');
    expect(result.shouldRefreshPrompt).toBe(false);
    expect(result.toast).toEqual({
      severity: 'success',
      message: 'Prompt published.',
    });
  });

  it('releases edit-mode prompts and marks refresh', async () => {
    const releasePrompt = vi.fn().mockResolvedValue(buildPrompt('prompt-20'));

    const result = await releasePromptAction({
      mode: 'edit',
      createdPromptId: null,
      promptId: 'prompt-20',
      releasePrompt,
    });

    expect(result.shouldRefreshPrompt).toBe(true);
    expect(result.toast).toEqual({
      severity: 'success',
      message: 'Prompt released.',
    });
  });

  it('shows requested messaging when the release state is requested', async () => {
    const releasePrompt = vi.fn().mockResolvedValue(buildPrompt('prompt-30', 'requested'));

    const result = await releasePromptAction({
      mode: 'edit',
      createdPromptId: null,
      promptId: 'prompt-30',
      releasePrompt,
    });

    expect(result.toast).toEqual({
      severity: 'success',
      message: 'Release requested.',
    });
  });

  it('returns display errors when release fails', async () => {
    const releasePrompt = vi.fn().mockRejectedValue(new Error('release failed'));

    const result = await releasePromptAction({
      mode: 'edit',
      createdPromptId: null,
      promptId: 'prompt-20',
      releasePrompt,
    });

    expect(result.toast).toEqual({
      severity: 'error',
      message: 'release failed',
    });
  });

  it('returns display errors when release metadata is missing', async () => {
    const promptWithoutMetadata = {
      ...buildPrompt('prompt-40'),
      extensions: undefined,
    } as PromptSpec;
    const releasePrompt = vi.fn().mockResolvedValue(promptWithoutMetadata);

    const result = await releasePromptAction({
      mode: 'edit',
      createdPromptId: null,
      promptId: 'prompt-40',
      releasePrompt,
    });

    expect(result.toast).toEqual({
      severity: 'error',
      message: 'Release response is missing x-promptlm metadata.',
    });
  });
});

describe('execution selection helpers', () => {
  const execution = (id: string, timestamp: string): PromptEditorExecution => ({
    id,
    timestamp,
    response: { content: id },
    placeholders: [],
    evaluations: [],
  });

  it('sorts merged executions newest first', () => {
    const merged = mergeExecutions(
      [execution('local-old', '2026-03-01T10:00:00.000Z')],
      [execution('remote-new', '2026-03-02T10:00:00.000Z')],
    );

    expect(merged.map((entry) => entry.id)).toEqual(['remote-new', 'local-old']);
  });

  it('selects the explicit execution id when present', () => {
    const executions = [execution('a', '2026-03-02T10:00:00.000Z'), execution('b', '2026-03-01T10:00:00.000Z')];
    expect(pickSelectedExecution(executions, 'b')?.id).toBe('b');
  });

  it('falls back to the newest execution when id is missing or unknown', () => {
    const executions = [execution('a', '2026-03-02T10:00:00.000Z'), execution('b', '2026-03-01T10:00:00.000Z')];
    expect(pickSelectedExecution(executions, null)?.id).toBe('a');
    expect(pickSelectedExecution(executions, 'missing')?.id).toBe('a');
  });
});
