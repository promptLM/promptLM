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

import {
  type StoreStatusEvent,
  type StoreStatusEventSourceFactory,
  type StoreStatusEventsSubscription,
  subscribeToStoreStatusEvents,
} from '@promptlm/api-client';

import { resolveGeneratedApiBaseUrl } from './generatedClientProvider';

// Prompt-execution SSE channel (issue #352). Reuses the StoreStatusEvent envelope
// but the address pattern and status enum are different from the store channel —
// see PromptSpecController.registerExecutionEvents on the backend.
const PROMPT_EXECUTION_EVENTS_CHANNEL_ADDRESS =
  '/api/prompts/{promptSpecId}/execution/events' as const;
const STATUS_EVENT_NAME = 'status' as const;

export type PromptExecutionStatusEventPayload = StoreStatusEvent;

const defaultEventSourceFactoryImpl = (url: string, init: EventSourceInit) => {
  if (typeof globalThis.EventSource !== 'function') {
    throw new Error('EventSource is not available in this environment');
  }
  return new globalThis.EventSource(url, init);
};

const joinBaseUrlInternal = (baseUrl: string, relativePath: string): string => {
  const normalizedBaseUrl = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
  return new URL(relativePath, normalizedBaseUrl).toString();
};

export type SubscribeToStoreOperationStatusOptions = {
  operationId: string;
  baseUrl?: string;
  withCredentials?: boolean;
  eventSourceFactory?: StoreStatusEventSourceFactory;
  onStatus?: (event: StoreStatusEvent) => void;
  onError?: (error: Error) => void;
};

export const createStoreOperationId = (): string => {
  const randomUUID = globalThis.crypto?.randomUUID?.bind(globalThis.crypto);
  if (typeof randomUUID === 'function') {
    return randomUUID();
  }

  return `store-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
};

export const isTerminalStoreStatusEvent = (event: StoreStatusEvent): boolean => {
  return event.status === 'completed' || event.status === 'failed';
};

export const subscribeToStoreOperationStatus = ({
  baseUrl,
  ...options
}: SubscribeToStoreOperationStatusOptions): StoreStatusEventsSubscription => {
  return subscribeToStoreStatusEvents({
    ...options,
    baseUrl: resolveGeneratedApiBaseUrl(baseUrl),
  });
};

export type SubscribeToPromptExecutionStatusOptions = {
  promptSpecId: string;
  baseUrl?: string;
  withCredentials?: boolean;
  eventSourceFactory?: StoreStatusEventSourceFactory;
  onStatus?: (event: StoreStatusEvent) => void;
  onError?: (error: Error) => void;
};

export const isTerminalPromptExecutionStatusEvent = (event: StoreStatusEvent): boolean => {
  // 'executed' alone is not terminal — 'pushed' may still follow. The UI treats
  // 'failed' or 'pushed' as terminal states for tearing down the subscription.
  return event.status === 'failed' || event.status === 'pushed';
};

export const subscribeToPromptExecutionStatus = ({
  promptSpecId,
  baseUrl,
  withCredentials = false,
  eventSourceFactory = defaultEventSourceFactoryImpl,
  onStatus,
  onError,
}: SubscribeToPromptExecutionStatusOptions): StoreStatusEventsSubscription => {
  const relativePath = PROMPT_EXECUTION_EVENTS_CHANNEL_ADDRESS.replace(
    '{promptSpecId}',
    encodeURIComponent(promptSpecId),
  );
  const resolvedBase = resolveGeneratedApiBaseUrl(baseUrl);
  const url = resolvedBase ? joinBaseUrlInternal(resolvedBase, relativePath) : relativePath;
  const eventSource = eventSourceFactory(url, { withCredentials });

  const statusListener: EventListener = (event) => {
    try {
      const messageEvent = event as MessageEvent<unknown>;
      const data = messageEvent.data;
      let parsed: StoreStatusEvent;
      if (typeof data === 'string') {
        parsed = JSON.parse(data) as StoreStatusEvent;
      } else if (data && typeof data === 'object') {
        parsed = data as StoreStatusEvent;
      } else {
        throw new Error('Prompt execution event payload must be a JSON object');
      }
      onStatus?.(parsed);
    } catch (error) {
      const normalizedError = error instanceof Error ? error : new Error(String(error));
      onError?.(normalizedError);
    }
  };

  const errorListener: EventListener = () => {
    onError?.(new Error('Prompt execution stream failed'));
  };

  eventSource.addEventListener(STATUS_EVENT_NAME, statusListener);
  eventSource.addEventListener('error', errorListener);

  return {
    close: () => {
      eventSource.removeEventListener(STATUS_EVENT_NAME, statusListener);
      eventSource.removeEventListener('error', errorListener);
      eventSource.close();
    },
  };
};
