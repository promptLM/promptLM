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
 * Issue #360 — shell-side hook that wraps `subscribeToPromptExecutionAfterSave`
 * with React lifecycle discipline. Keeps the subscription close-fn in a ref so
 * React 18 strict-mode double-effects don't leak `EventSource` connections,
 * and exposes a five-state machine to the response-panel UI:
 *
 *   idle → executing → executed → pushed
 *                   ↘ failed (with Retry)
 *
 * The hook auto-closes the subscription when a terminal event arrives
 * (`failed` / `pushed`), when the consumer triggers a new save, and on
 * component unmount.
 */
import { useCallback, useEffect, useRef, useState } from 'react';

import type { StoreStatusEvent } from '@promptlm/api-client';

import {
  subscribeToPromptExecutionAfterSave,
  type PromptExecutionSubscriptionHandlers,
} from './editorActions';

export type PromptExecutionState = 'idle' | 'executing' | 'executed' | 'failed' | 'pushed';

export type PromptExecutionSubscriptionApi = {
  state: PromptExecutionState;
  lastEvent: StoreStatusEvent | null;
  errorMessage: string | null;
  /**
   * Begin tracking executions for `promptSpecId`. Tearing down the previous
   * subscription (if any) is automatic so each save replaces the prior stream.
   * `onExecuted` runs after the React state transition so callers can refetch
   * the prompt to refresh the displayed response.
   */
  start: (
    promptSpecId: string,
    callbacks?: {
      onExecuted?: () => void | Promise<void>;
      onPushed?: () => void | Promise<void>;
    },
  ) => void;
  /** Reset to `idle` and close the active subscription. */
  reset: () => void;
};

type Subscribe = (
  options: PromptExecutionSubscriptionHandlers,
) => { close: () => void };

export type UsePromptExecutionSubscriptionOptions = {
  /**
   * Test seam — production code uses the real exported helper, vitest tests
   * inject a synchronous fake so SSE events can be driven by hand.
   */
  subscribe?: Subscribe;
};

export const usePromptExecutionSubscription = (
  options: UsePromptExecutionSubscriptionOptions = {},
): PromptExecutionSubscriptionApi => {
  const subscribe = options.subscribe ?? subscribeToPromptExecutionAfterSave;

  const [state, setState] = useState<PromptExecutionState>('idle');
  const [lastEvent, setLastEvent] = useState<StoreStatusEvent | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // Keep the close-fn in a ref. React 18 strict-mode invokes effects twice in
  // dev; storing the live subscription handle in a ref means the cleanup path
  // (or a fresh `start` call) can tear down the previous EventSource even if
  // the surrounding component re-renders or the effect double-fires.
  const closeRef = useRef<(() => void) | null>(null);

  const closeActive = useCallback(() => {
    const close = closeRef.current;
    closeRef.current = null;
    if (close) {
      try {
        close();
      } catch {
        // Best-effort cleanup — ignore errors from a closed EventSource.
      }
    }
  }, []);

  const start = useCallback<PromptExecutionSubscriptionApi['start']>(
    (promptSpecId, callbacks) => {
      // Tear down any in-flight subscription before opening a new one.
      closeActive();
      setErrorMessage(null);
      setLastEvent(null);
      setState('executing');

      const subscription = subscribe({
        promptSpecId,
        onExecuting: (event) => {
          setLastEvent(event);
          setState('executing');
        },
        onExecuted: (event) => {
          setLastEvent(event);
          setState('executed');
          // Fire-and-forget — the host typically refetches the prompt to
          // pull the persisted response payload. Errors are surfaced via
          // the caller's own error boundary; we don't want to flip the
          // execution state on refetch failure.
          void Promise.resolve(callbacks?.onExecuted?.()).catch(() => undefined);
        },
        onFailed: (event) => {
          setLastEvent(event);
          setErrorMessage(event.message || 'Execution failed');
          setState('failed');
        },
        onPushed: (event) => {
          setLastEvent(event);
          setState('pushed');
          void Promise.resolve(callbacks?.onPushed?.()).catch(() => undefined);
        },
        onError: (error) => {
          setErrorMessage(error.message || 'Execution stream failed');
          setState('failed');
        },
      });
      closeRef.current = subscription.close;
    },
    [closeActive, subscribe],
  );

  const reset = useCallback(() => {
    closeActive();
    setState('idle');
    setLastEvent(null);
    setErrorMessage(null);
  }, [closeActive]);

  // Unmount cleanup. Guard against a strict-mode double-mount leaking the
  // EventSource by always closing whatever the ref currently points at.
  useEffect(() => {
    return () => {
      closeActive();
    };
  }, [closeActive]);

  return { state, lastEvent, errorMessage, start, reset };
};
