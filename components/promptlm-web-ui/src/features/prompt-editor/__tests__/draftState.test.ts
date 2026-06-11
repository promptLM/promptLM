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

import { describe, expect, it } from 'vitest';

import type { PromptSpec } from '@promptlm/api-client';

import {
  createEmptyPromptDraft,
  createPromptDraftFromPrompt,
  promptEditorReducer,
  sanitizePromptDraft,
} from '../draftState';

describe('promptEditorReducer', () => {
  it('adds and updates tool messages through the shared message state', () => {
    const initial = createEmptyPromptDraft();
    const withTool = promptEditorReducer(initial, { type: 'add-message', role: 'tool' });
    const toolIndex = withTool.draft.request.messages.length - 1;
    const next = promptEditorReducer(withTool, {
      type: 'update-message',
      index: toolIndex,
      field: 'name',
      value: 'inventory.search',
    });

    const toolMessage = next.draft.request.messages.at(-1);
    expect(toolMessage?.role).toBe('tool');
    expect(toolMessage?.name).toBe('inventory.search');
  });

  it('keeps only one system message at the front when sanitizing', () => {
    const initial = createEmptyPromptDraft();
    const draft = {
      ...initial.draft,
      name: '  prompt-name  ',
      group: '  support  ',
      request: {
        ...initial.draft.request,
        messages: [
          { id: 'sys-1', role: 'system', content: 'First system' },
          { id: 'sys-2', role: 'system', content: 'Second system' },
          { id: 'usr-1', role: 'user', content: 'Hello' },
        ],
      },
      evaluations: [{ evaluator: 'qa', type: 'automatic', description: '  check  ' }],
    };

    const sanitized = sanitizePromptDraft(draft, true, 'https://example.com/repo');

    expect(sanitized.name).toBe('prompt-name');
    expect(sanitized.group).toBe('support');
    expect(sanitized.request.messages[0]?.role).toBe('system');
    expect(sanitized.request.messages[1]?.role).toBe('assistant');
    expect(sanitized.repositoryUrl).toBe('https://example.com/repo');
    expect(sanitized.evaluations).toEqual([
      { evaluator: 'qa', type: 'automatic', description: 'check' },
    ]);
  });
});

describe('createEmptyPromptDraft', () => {
  // Issue #309 — the New-prompt view must open blank. The empty draft is what
  // the editor falls back to before the backend template lands and what it
  // also lands on once the backend returns the blank /api/prompts/template
  // payload (see DefaultPromptLifecycleService#createDefaultPromptSpec).
  it('starts with blank name, group, description, messages and placeholders', () => {
    const state = createEmptyPromptDraft();

    expect(state.draft.name).toBe('');
    expect(state.draft.group).toBe('');
    expect(state.draft.description).toBe('');
    expect(state.draft.request.vendor).toBe('');
    expect(state.draft.request.model).toBe('');
    expect(state.draft.request.messages).toHaveLength(2);
    expect(state.draft.request.messages[0]?.role).toBe('system');
    expect(state.draft.request.messages[0]?.content).toBe('');
    expect(state.draft.request.messages[1]?.role).toBe('user');
    expect(state.draft.request.messages[1]?.content).toBe('');
    expect(state.draft.placeholders.list).toEqual([]);
    expect(state.draft.placeholders.startPattern).toBe('{{');
    expect(state.draft.placeholders.endPattern).toBe('}}');
    expect(state.evaluationEnabled).toBe(false);
  });
});

describe('createPromptDraftFromPrompt', () => {
  // Issue #309 — the New-prompt editor hydrates from the backend `/template`
  // payload. That payload is now blank by design, and the hydration must not
  // re-introduce demo content (name/group/messages/placeholders).
  it('keeps all fields blank when the backend template is blank', () => {
    const blankTemplate: PromptSpec = {
      name: '',
      group: '',
      description: '',
      version: '1.0.0-SNAPSHOT',
      revision: 1,
      request: {
        type: 'chat/completion',
        vendor: '',
        model: '',
        messages: [
          { role: 'system', content: '' },
          { role: 'user', content: '' },
        ],
      } as PromptSpec['request'],
      placeholders: {
        startPattern: '{{',
        endPattern: '}}',
        list: [],
      },
    } as PromptSpec;

    const state = createPromptDraftFromPrompt(blankTemplate);

    expect(state.draft.name).toBe('');
    expect(state.draft.group).toBe('');
    expect(state.draft.description).toBe('');
    expect(state.draft.request.vendor).toBe('');
    expect(state.draft.request.model).toBe('');
    expect(state.draft.request.messages).toHaveLength(2);
    expect(state.draft.request.messages[0]?.content).toBe('');
    expect(state.draft.request.messages[1]?.content).toBe('');
    expect(state.draft.placeholders.list).toEqual([]);
    expect(state.evaluationEnabled).toBe(false);
  });

  it('hydrates evaluation definitions from prompt extensions', () => {
    const prompt: PromptSpec = {
      id: 'prompt-1',
      name: 'Support Prompt',
      group: 'support',
      description: 'Existing prompt',
      request: {
        type: 'chat/completion',
        vendor: 'openai',
        model: 'gpt-4o',
        messages: [
          { role: 'SYSTEM', content: 'System message' },
          { role: 'USER', content: 'User message' },
        ],
      } as PromptSpec['request'],
      placeholders: {
        startPattern: '{{',
        endPattern: '}}',
        list: [{ name: 'customer_name', defaultValue: 'Taylor' }],
      },
      extensions: {
        'x-evaluation': {
          spec: {
            evaluations: [
              { evaluator: 'policy-check', type: 'automatic', description: 'Validate policy wording' },
            ],
          },
        },
      } as PromptSpec['extensions'],
    };

    const state = createPromptDraftFromPrompt(prompt);

    expect(state.evaluationEnabled).toBe(true);
    expect(state.draft.request.messages).toHaveLength(2);
    expect(state.draft.evaluations).toEqual([
      { evaluator: 'policy-check', type: 'automatic', description: 'Validate policy wording' },
    ]);
  });
});
