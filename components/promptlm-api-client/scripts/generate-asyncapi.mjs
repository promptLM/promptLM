import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { compile } from 'json-schema-to-typescript';
import YAML from 'yaml';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const workspaceRoot = path.resolve(__dirname, '..');
const specPath = path.join(workspaceRoot, 'spec/asyncapi/store-status.asyncapi.yaml');
const outputPath = path.join(workspaceRoot, 'src/generated/asyncapi.ts');

const source = await readFile(specPath, 'utf8');
const spec = YAML.parse(source);

const resolveRef = (root, ref) => {
  if (typeof ref !== 'string' || !ref.startsWith('#/')) {
    throw new Error(`Unsupported AsyncAPI ref: ${String(ref)}`);
  }

  return ref
    .slice(2)
    .split('/')
    .reduce((value, key) => {
      if (!value || typeof value !== 'object' || !(key in value)) {
        throw new Error(`Could not resolve AsyncAPI ref: ${ref}`);
      }
      return value[key];
    }, root);
};

const [channelKey, channelDefinition] = Object.entries(spec.channels ?? {})[0] ?? [];
if (!channelKey || !channelDefinition) {
  throw new Error('Expected exactly one AsyncAPI channel definition for store status events.');
}

const [messageKey, messageDefinition] = Object.entries(channelDefinition.messages ?? {})[0] ?? [];
if (!messageKey || !messageDefinition) {
  throw new Error(`Channel "${channelKey}" does not define a message.`);
}

const resolvedMessage = messageDefinition.$ref ? resolveRef(spec, messageDefinition.$ref) : messageDefinition;
const payloadSchema = resolvedMessage.payload;
if (!payloadSchema) {
  throw new Error(`Message "${messageKey}" does not define a payload schema.`);
}

const eventName = resolvedMessage.name ?? messageKey;
const channelAddress = channelDefinition.address;
if (typeof channelAddress !== 'string' || channelAddress.length === 0) {
  throw new Error(`Channel "${channelKey}" must define an address.`);
}

const parameterEntries = Object.keys(channelDefinition.parameters ?? {});
if (parameterEntries.length !== 1 || parameterEntries[0] !== 'operationId') {
  throw new Error('The store status channel must expose exactly one operationId address parameter.');
}

const payloadTypes = await compile(payloadSchema, 'StoreStatusEvent', {
  bannerComment: '',
  format: false,
});

const output = `/*
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
${payloadTypes.trim()}

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

export const STORE_STATUS_EVENTS_CHANNEL_ADDRESS = ${JSON.stringify(channelAddress)} as const;
export const STORE_STATUS_EVENT_NAME = ${JSON.stringify(eventName)} as const;

const defaultEventSourceFactory: StoreStatusEventSourceFactory = (url, init) => {
  if (typeof globalThis.EventSource !== 'function') {
    throw new Error('EventSource is not available in this environment');
  }

  return new globalThis.EventSource(url, init);
};

const joinBaseUrl = (baseUrl: string, relativePath: string): string => {
  const normalizedBaseUrl = baseUrl.endsWith('/') ? baseUrl : \`\${baseUrl}/\`;
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
`;

await mkdir(path.dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${output}\n`);

console.log(`Generated AsyncAPI client: ${path.relative(workspaceRoot, outputPath)}`);
