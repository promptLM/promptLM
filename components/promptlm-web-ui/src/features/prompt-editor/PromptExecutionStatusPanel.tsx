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
 * Issue #360 — visual surface for the event-driven save-and-execute pipeline
 * introduced in #352. The panel is rendered between the sticky header and the
 * messages editor whenever the shell is actively tracking a save's
 * subsequent execution (i.e. `state !== 'idle'`). Four states are surfaced:
 *
 *   executing → spinner + "Running…"
 *   executed  → confirmation tile (the underlying response refresh is the
 *               shell's responsibility — this panel just confirms the run
 *               landed; the response itself shows in the run-response panel
 *               below once the prompt is refetched)
 *   failed    → error block + Retry button
 *   pushed    → transient "Pushed to GitHub" pill (auto-hidden by the shell)
 */
import * as React from 'react';

import type { PromptExecutionState } from './usePromptExecutionSubscription';

export interface PromptExecutionStatusPanelProps {
  state: PromptExecutionState;
  errorMessage?: string | null;
  /** Click handler for the Retry button rendered in the `failed` state. */
  onRetry?: () => void;
  /** Whether a retry attempt is currently in flight (disables the button). */
  isRetrying?: boolean;
}

const baseCard: React.CSSProperties = {
  marginBottom: 14,
  padding: '10px 14px',
  borderRadius: 6,
  fontFamily: 'var(--pl-display)',
  fontSize: 13,
  display: 'flex',
  alignItems: 'center',
  gap: 12,
};

export const PromptExecutionStatusPanel: React.FC<PromptExecutionStatusPanelProps> = ({
  state,
  errorMessage,
  onRetry,
  isRetrying = false,
}) => {
  if (state === 'idle') {
    return null;
  }

  if (state === 'executing') {
    return (
      <div
        role="status"
        aria-live="polite"
        data-testid="prompt-execution-status-executing"
        style={{
          ...baseCard,
          background: 'oklch(0.97 0.03 240)',
          border: '1px solid oklch(0.86 0.04 240)',
          color: 'var(--pl-signal-ink)',
        }}
      >
        <span
          aria-hidden
          style={{
            width: 14,
            height: 14,
            borderRadius: 999,
            border: '2px solid oklch(0.86 0.04 240)',
            borderTopColor: 'var(--pl-signal-deep)',
            animation: 'pl-exec-spin 0.8s linear infinite',
            flexShrink: 0,
          }}
        />
        <span>Running…</span>
        <style>{`@keyframes pl-exec-spin { to { transform: rotate(360deg); } }`}</style>
      </div>
    );
  }

  if (state === 'executed') {
    return (
      <div
        role="status"
        aria-live="polite"
        data-testid="prompt-execution-status-executed"
        style={{
          ...baseCard,
          background: 'oklch(0.97 0.04 155)',
          border: '1px solid oklch(0.86 0.05 155)',
          color: 'oklch(0.34 0.10 155)',
        }}
      >
        <span
          aria-hidden
          style={{
            width: 8,
            height: 8,
            borderRadius: 999,
            background: 'oklch(0.55 0.13 155)',
            flexShrink: 0,
          }}
        />
        <span>Response ready. Awaiting push to GitHub…</span>
      </div>
    );
  }

  if (state === 'failed') {
    return (
      <div
        role="alert"
        data-testid="prompt-execution-status-failed"
        style={{
          ...baseCard,
          background: 'oklch(0.97 0.03 25)',
          border: '1px solid oklch(0.86 0.05 25)',
          color: 'oklch(0.42 0.13 25)',
          alignItems: 'flex-start',
        }}
      >
        <span
          aria-hidden
          style={{
            width: 8,
            height: 8,
            marginTop: 5,
            borderRadius: 999,
            background: 'oklch(0.55 0.15 25)',
            flexShrink: 0,
          }}
        />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontWeight: 500, marginBottom: 2 }}>Execution failed</div>
          <div style={{ fontSize: 12, color: 'oklch(0.45 0.10 25)', wordBreak: 'break-word' }}>
            {errorMessage ?? 'The prompt run did not complete.'}
          </div>
        </div>
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            disabled={isRetrying}
            data-testid="prompt-execution-status-retry"
            style={{
              flexShrink: 0,
              padding: '6px 12px',
              background: 'oklch(0.45 0.13 25)',
              color: 'var(--pl-paper)',
              border: 'none',
              borderRadius: 5,
              fontFamily: 'var(--pl-display)',
              fontSize: 12,
              fontWeight: 500,
              cursor: isRetrying ? 'wait' : 'pointer',
              opacity: isRetrying ? 0.6 : 1,
            }}
          >
            {isRetrying ? 'Retrying…' : 'Retry'}
          </button>
        )}
      </div>
    );
  }

  // pushed
  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="prompt-execution-status-pushed"
      style={{
        ...baseCard,
        background: 'oklch(0.97 0.04 155)',
        border: '1px solid oklch(0.86 0.05 155)',
        color: 'oklch(0.34 0.10 155)',
      }}
    >
      <span
        aria-hidden
        style={{
          width: 8,
          height: 8,
          borderRadius: 999,
          background: 'oklch(0.55 0.13 155)',
          flexShrink: 0,
        }}
      />
      <span>Pushed to GitHub</span>
    </div>
  );
};
