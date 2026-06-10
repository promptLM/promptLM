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

import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StoreStatusEvent } from '@promptlm/api-client';

import {
  createStoreOperationId,
  isTerminalPromptExecutionStatusEvent,
  isTerminalStoreStatusEvent,
  subscribeToStoreOperationStatus,
} from '@api-common/storeStatusEvents';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('storeStatusEvents', () => {
  it('subscribes to the generated store status stream and parses typed events', () => {
    let statusListener: EventListener | null = null;
    let errorListener: EventListener | null = null;
    let closed = false;
    let url = '';
    let withCredentials = false;
    const received: StoreStatusEvent[] = [];
    const errors: Error[] = [];

    const subscription = subscribeToStoreOperationStatus({
      operationId: 'clone/op',
      baseUrl: 'http://localhost:8085',
      eventSourceFactory: (nextUrl, init) => {
        url = nextUrl;
        withCredentials = Boolean(init.withCredentials);

        return {
          addEventListener: (type, listener) => {
            if (type === 'status') {
              statusListener = listener;
            }
            if (type === 'error') {
              errorListener = listener;
            }
          },
          removeEventListener: () => undefined,
          close: () => {
            closed = true;
          },
        };
      },
      onStatus: (event) => {
        received.push(event);
      },
      onError: (error) => {
        errors.push(error);
      },
    });

    statusListener?.({
      data: JSON.stringify({
        operation: 'store',
        status: 'progress',
        message: 'Clone in progress',
        timestamp: '2026-03-20T10:00:00.000Z',
        details: {
          operationId: 'clone/op',
          phase: 'clone',
        },
      }),
    } as MessageEvent<string>);
    errorListener?.(new Event('error'));

    expect(url).toBe('http://localhost:8085/api/store/events/clone%2Fop');
    expect(withCredentials).toBe(false);
    expect(received).toEqual([
      {
        operation: 'store',
        status: 'progress',
        message: 'Clone in progress',
        timestamp: '2026-03-20T10:00:00.000Z',
        details: {
          operationId: 'clone/op',
          phase: 'clone',
        },
      },
    ]);
    expect(errors).toHaveLength(1);
    expect(errors[0]?.message).toContain('stream failed');

    subscription.close();
    expect(closed).toBe(true);
  });

  it('falls back to the window origin and uses the browser UUID generator when available', () => {
    vi.stubGlobal('window', { location: { origin: 'http://from-window' } });
    vi.stubGlobal('crypto', {
      randomUUID: () => 'generated-op-id',
    });

    let url = '';

    const subscription = subscribeToStoreOperationStatus({
      operationId: createStoreOperationId(),
      eventSourceFactory: (nextUrl) => {
        url = nextUrl;
        return {
          addEventListener: () => undefined,
          removeEventListener: () => undefined,
          close: () => undefined,
        };
      },
    });

    expect(url).toBe('http://from-window/api/store/events/generated-op-id');
    subscription.close();
  });

  it('identifies terminal events from the generated status union', () => {
    expect(
      isTerminalStoreStatusEvent({
        operation: 'store',
        status: 'completed',
        message: 'Clone complete',
        timestamp: '2026-03-20T10:00:00.000Z',
      }),
    ).toBe(true);

    expect(
      isTerminalStoreStatusEvent({
        operation: 'store',
        status: 'failed',
        message: 'Clone failed',
        timestamp: '2026-03-20T10:00:00.000Z',
      }),
    ).toBe(true);

    expect(
      isTerminalStoreStatusEvent({
        operation: 'store',
        status: 'progress',
        message: 'Still cloning',
        timestamp: '2026-03-20T10:00:00.000Z',
      }),
    ).toBe(false);
  });

  it('treats failed, pushed, and push-failed as terminal prompt-execution states', () => {
    // Issue #361: `push-failed` joins `failed` and `pushed` as terminal states so the
    // editor subscription tears down once the server has reported a definitive outcome.
    const at = '2026-04-01T10:00:00.000Z';

    expect(
      isTerminalPromptExecutionStatusEvent({
        operation: 'prompt-execution',
        status: 'failed',
        message: 'Prompt execution failed',
        timestamp: at,
      }),
    ).toBe(true);

    expect(
      isTerminalPromptExecutionStatusEvent({
        operation: 'prompt-execution',
        status: 'pushed',
        message: 'Pushed to GitHub',
        timestamp: at,
      }),
    ).toBe(true);

    expect(
      isTerminalPromptExecutionStatusEvent({
        operation: 'prompt-execution',
        status: 'push-failed',
        message: 'Push to GitHub failed',
        timestamp: at,
      }),
    ).toBe(true);

    // Non-terminal intermediate states are still streamed through.
    expect(
      isTerminalPromptExecutionStatusEvent({
        operation: 'prompt-execution',
        status: 'executed',
        message: 'Prompt executed',
        timestamp: at,
      }),
    ).toBe(false);

    expect(
      isTerminalPromptExecutionStatusEvent({
        operation: 'prompt-execution',
        status: 'executing',
        message: 'Executing prompt',
        timestamp: at,
      }),
    ).toBe(false);
  });
});
