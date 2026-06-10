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

import React, { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createRoot } from 'react-dom/client';

import { PromptExecutionStatusPanel } from '../PromptExecutionStatusPanel';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const mount = (element: React.ReactElement) => {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(element);
  });
  return {
    container,
    rerender: (next: React.ReactElement) => {
      act(() => {
        root.render(next);
      });
    },
    unmount: () => {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
};

afterEach(() => {
  document.body.innerHTML = '';
});

describe('PromptExecutionStatusPanel', () => {
  it('renders nothing in the idle state', () => {
    const { container, unmount } = mount(<PromptExecutionStatusPanel state="idle" />);
    expect(container.textContent).toBe('');
    unmount();
  });

  it('shows a running indicator while executing', () => {
    const { container, unmount } = mount(<PromptExecutionStatusPanel state="executing" />);
    expect(container.querySelector('[data-testid="prompt-execution-status-executing"]')).not.toBeNull();
    expect(container.textContent).toContain('Running');
    unmount();
  });

  it('shows a "response ready" tile after the executed event', () => {
    const { container, unmount } = mount(<PromptExecutionStatusPanel state="executed" />);
    expect(
      container.querySelector('[data-testid="prompt-execution-status-executed"]'),
    ).not.toBeNull();
    unmount();
  });

  it('shows the error and wires the Retry button on failure', () => {
    const onRetry = vi.fn();
    const { container, unmount } = mount(
      <PromptExecutionStatusPanel
        state="failed"
        errorMessage="upstream timed out"
        onRetry={onRetry}
      />,
    );
    expect(container.textContent).toContain('upstream timed out');
    const retry = container.querySelector(
      '[data-testid="prompt-execution-status-retry"]',
    ) as HTMLButtonElement | null;
    expect(retry).not.toBeNull();
    act(() => {
      retry?.click();
    });
    expect(onRetry).toHaveBeenCalledTimes(1);
    unmount();
  });

  it('disables Retry while a retry is in flight', () => {
    const { container, unmount } = mount(
      <PromptExecutionStatusPanel state="failed" onRetry={vi.fn()} isRetrying />,
    );
    const retry = container.querySelector(
      '[data-testid="prompt-execution-status-retry"]',
    ) as HTMLButtonElement | null;
    expect(retry?.disabled).toBe(true);
    expect(retry?.textContent).toContain('Retrying');
    unmount();
  });

  it('renders the transient "Pushed to GitHub" pill in the pushed state', () => {
    const { container, unmount } = mount(<PromptExecutionStatusPanel state="pushed" />);
    expect(
      container.querySelector('[data-testid="prompt-execution-status-pushed"]'),
    ).not.toBeNull();
    expect(container.textContent).toContain('Pushed to GitHub');
    unmount();
  });
});
