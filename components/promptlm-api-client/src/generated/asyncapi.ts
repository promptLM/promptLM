/*
 * Copyright 2025 promptLM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* generated from spec/asyncapi/store-status.asyncapi.yaml -- do not edit */
export interface StoreStatusEvent {
/**
 * Logical operation category producing the event.
 */
operation: string
/**
 * Current lifecycle state for the stream or operation.
 * 
 * The `connected`, `started`, `progress`, `completed`, and `failed`
 * values are emitted by the store operation channel. The
 * `executing`, `executed`, `pushed`, and `push-failed` values are
 * emitted by the prompt-execution channel (issue #352 deferred-push
 * save flow; `push-failed` added in #361).
 * 
 */
status: ("connected" | "started" | "progress" | "completed" | "failed" | "executing" | "executed" | "pushed" | "push-failed")
/**
 * Human-readable status message.
 */
message: string
/**
 * ISO-8601 timestamp for when the event was emitted.
 */
timestamp: string
/**
 * Optional structured metadata for the event.
 */
details?: {
[k: string]: unknown
}
}

export type StoreStatusEventsParameters = {
  operationId: string;
};

export type StoreStatusEventSourceLike = {
  addEventListener(type: string, listener: EventListener): void;
  removeEventListener(type: string, listener: EventListener): void;
  close(): void;
};

export type StoreStatusEventSourceFactory = (url: string, init: EventSourceInit) => StoreStatusEventSourceLike;

export type StoreStatusEventsSubscription = {
  close: () => void;
};

export type StoreStatusEventsSubscriptionOptions = StoreStatusEventsParameters & {
  baseUrl?: string;
  withCredentials?: boolean;
  eventSourceFactory?: StoreStatusEventSourceFactory;
  onStatus?: (event: StoreStatusEvent) => void;
  onError?: (error: Error) => void;
};

export const STORE_STATUS_EVENTS_CHANNEL_ADDRESS = "/api/store/events/{operationId}" as const;
export const STORE_STATUS_EVENT_NAME = "status" as const;

const defaultEventSourceFactory: StoreStatusEventSourceFactory = (url, init) => {
  if (typeof globalThis.EventSource !== 'function') {
    throw new Error('EventSource is not available in this environment');
  }

  return new globalThis.EventSource(url, init);
};

const joinBaseUrl = (baseUrl: string, relativePath: string): string => {
  const normalizedBaseUrl = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
  return new URL(relativePath, normalizedBaseUrl).toString();
};

export const buildStoreStatusEventsUrl = ({ operationId }: StoreStatusEventsParameters, baseUrl = ''): string => {
  const relativePath = STORE_STATUS_EVENTS_CHANNEL_ADDRESS.replace('{operationId}', encodeURIComponent(operationId));
  return baseUrl ? joinBaseUrl(baseUrl, relativePath) : relativePath;
};

export const parseStoreStatusEventData = (data: unknown): StoreStatusEvent => {
  if (typeof data === 'string') {
    return JSON.parse(data) as StoreStatusEvent;
  }

  if (data && typeof data === 'object') {
    return data as StoreStatusEvent;
  }

  throw new Error('Store status event payload must be a JSON object');
};

export const subscribeToStoreStatusEvents = ({
  operationId,
  baseUrl = '',
  withCredentials = false,
  eventSourceFactory = defaultEventSourceFactory,
  onStatus,
  onError,
}: StoreStatusEventsSubscriptionOptions): StoreStatusEventsSubscription => {
  const eventSource = eventSourceFactory(buildStoreStatusEventsUrl({ operationId }, baseUrl), { withCredentials });

  const statusListener: EventListener = (event) => {
    try {
      const messageEvent = event as MessageEvent<unknown>;
      onStatus?.(parseStoreStatusEventData(messageEvent.data));
    } catch (error) {
      const normalizedError = error instanceof Error ? error : new Error(String(error));
      onError?.(normalizedError);
    }
  };

  const errorListener: EventListener = () => {
    onError?.(new Error('Store status stream failed'));
  };

  eventSource.addEventListener(STORE_STATUS_EVENT_NAME, statusListener);
  eventSource.addEventListener('error', errorListener);

  return {
    close: () => {
      eventSource.removeEventListener(STORE_STATUS_EVENT_NAME, statusListener);
      eventSource.removeEventListener('error', errorListener);
      eventSource.close();
    },
  };
};

