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

/** @vitest-environment jsdom */

/**
 * Issue #360 — verifies that the prompt-execution subscription hook drives
 * the four-state transitions (executing → executed → pushed; executing →
 * failed) and cleans the underlying EventSource up on unmount as well as on
 * each new `start()` call. The fake SSE source lets us trigger events
 * synchronously without standing up a server.
 */

import React, { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createRoot, type Root } from 'react-dom/client';

import type { StoreStatusEvent } from '@promptlm/api-client';

import { usePromptExecutionSubscription } from '../usePromptExecutionSubscription';
import type { PromptExecutionSubscriptionHandlers } from '../editorActions';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

type SubscribeCapture = PromptExecutionSubscriptionHandlers & {
  close: ReturnType<typeof vi.fn>;
};

const makeSubscribeFake = () => {
  const captures: SubscribeCapture[] = [];
  const subscribe = (options: PromptExecutionSubscriptionHandlers) => {
    const close = vi.fn();
    captures.push({ ...options, close });
    return { close };
  };
  return { subscribe, captures };
};

const buildEvent = (status: StoreStatusEvent['status']): StoreStatusEvent => ({
  operation: 'prompt-execution',
  status,
  message: `${status} message`,
  timestamp: '2026-06-09T12:00:00.000Z',
});

type Harness = {
  state: ReturnType<typeof usePromptExecutionSubscription>;
  recorded: Array<ReturnType<typeof usePromptExecutionSubscription>>;
};

const Probe: React.FC<{
  subscribe: ReturnType<typeof makeSubscribeFake>['subscribe'];
  harness: Harness;
}> = ({ subscribe, harness }) => {
  const api = usePromptExecutionSubscription({ subscribe });
  harness.state = api;
  harness.recorded.push(api);
  return (
    <div>
      <span data-testid="state">{api.state}</span>
      <span data-testid="error">{api.errorMessage ?? ''}</span>
    </div>
  );
};

const mountProbe = (subscribe: ReturnType<typeof makeSubscribeFake>['subscribe']) => {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  const harness: Harness = {
    state: {} as Harness['state'],
    recorded: [],
  };
  act(() => {
    root.render(<Probe subscribe={subscribe} harness={harness} />);
  });
  return { root, container, harness };
};

const unmount = (root: Root, container: HTMLElement) => {
  act(() => {
    root.unmount();
  });
  container.remove();
};

describe('usePromptExecutionSubscription', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('transitions idle → executing on start and surfaces the latest event', () => {
    const fake = makeSubscribeFake();
    const { root, container, harness } = mountProbe(fake.subscribe);
    expect(harness.state.state).toBe('idle');

    act(() => {
      harness.state.start('prompt-1');
    });
    expect(harness.state.state).toBe('executing');
    expect(fake.captures).toHaveLength(1);

    // The backend may emit an explicit `executing` event after the channel
    // opens; the hook updates `lastEvent` without re-flipping the state.
    act(() => {
      fake.captures[0].onExecuting?.(buildEvent('executing'));
    });
    expect(harness.state.state).toBe('executing');
    expect(harness.state.lastEvent?.status).toBe('executing');

    unmount(root, container);
  });

  it('transitions executing → executed and invokes onExecuted callback', async () => {
    const fake = makeSubscribeFake();
    const { root, container, harness } = mountProbe(fake.subscribe);
    const onExecuted = vi.fn().mockResolvedValue(undefined);

    act(() => {
      harness.state.start('prompt-1', { onExecuted });
    });
    act(() => {
      fake.captures[0].onExecuted?.(buildEvent('executed'));
    });
    expect(harness.state.state).toBe('executed');
    expect(onExecuted).toHaveBeenCalledTimes(1);

    unmount(root, container);
  });

  it('transitions executing → failed and records the error message', () => {
    const fake = makeSubscribeFake();
    const { root, container, harness } = mountProbe(fake.subscribe);

    act(() => {
      harness.state.start('prompt-1');
    });
    act(() => {
      fake.captures[0].onFailed?.({ ...buildEvent('failed'), message: 'boom' });
    });
    expect(harness.state.state).toBe('failed');
    expect(harness.state.errorMessage).toBe('boom');

    unmount(root, container);
  });

  it('transitions executing → executed → pushed and runs onPushed callback', async () => {
    const fake = makeSubscribeFake();
    const { root, container, harness } = mountProbe(fake.subscribe);
    const onPushed = vi.fn().mockResolvedValue(undefined);

    act(() => {
      harness.state.start('prompt-1', { onPushed });
    });
    act(() => {
      fake.captures[0].onExecuted?.(buildEvent('executed'));
    });
    expect(harness.state.state).toBe('executed');
    act(() => {
      fake.captures[0].onPushed?.(buildEvent('pushed'));
    });
    expect(harness.state.state).toBe('pushed');
    expect(onPushed).toHaveBeenCalledTimes(1);

    unmount(root, container);
  });

  it('closes the previous subscription when start() is called again', () => {
    const fake = makeSubscribeFake();
    const { root, container, harness } = mountProbe(fake.subscribe);

    act(() => {
      harness.state.start('prompt-1');
    });
    act(() => {
      harness.state.start('prompt-2');
    });
    expect(fake.captures).toHaveLength(2);
    expect(fake.captures[0].close).toHaveBeenCalledTimes(1);
    expect(fake.captures[1].close).not.toHaveBeenCalled();

    unmount(root, container);
  });

  it('closes the active subscription on unmount (no EventSource leak)', () => {
    const fake = makeSubscribeFake();
    const { root, container, harness } = mountProbe(fake.subscribe);

    act(() => {
      harness.state.start('prompt-1');
    });
    expect(fake.captures[0].close).not.toHaveBeenCalled();

    unmount(root, container);
    expect(fake.captures[0].close).toHaveBeenCalledTimes(1);
  });

  it('reset() returns to idle and closes the live stream', () => {
    const fake = makeSubscribeFake();
    const { root, container, harness } = mountProbe(fake.subscribe);

    act(() => {
      harness.state.start('prompt-1');
    });
    act(() => {
      harness.state.reset();
    });
    expect(harness.state.state).toBe('idle');
    expect(fake.captures[0].close).toHaveBeenCalledTimes(1);

    unmount(root, container);
  });

  it('flips to failed when the SSE stream itself errors', () => {
    const fake = makeSubscribeFake();
    const { root, container, harness } = mountProbe(fake.subscribe);

    act(() => {
      harness.state.start('prompt-1');
    });
    act(() => {
      fake.captures[0].onError?.(new Error('stream broke'));
    });
    expect(harness.state.state).toBe('failed');
    expect(harness.state.errorMessage).toBe('stream broke');

    unmount(root, container);
  });
});
