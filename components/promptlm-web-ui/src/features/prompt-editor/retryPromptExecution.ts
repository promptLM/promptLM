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
 * Issue #360 — thin client for `POST /api/prompts/{id}/execute/retry`. The
 * generated OpenAPI client doesn't expose this operation yet (the backend
 * added it in #352 alongside the SSE channel) so the editor calls fetch
 * directly. The actual response status drives the next subscription: any
 * 2xx means the backend has accepted the retry and the editor should
 * resubscribe to the prompt-execution stream.
 */
import { resolveGeneratedApiBaseUrl } from '@api-common/generatedClientProvider';

const joinUrl = (baseUrl: string, relativePath: string): string => {
  if (!baseUrl) return relativePath;
  const normalizedBase = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
  const normalizedRel = relativePath.startsWith('/') ? relativePath.slice(1) : relativePath;
  return new URL(normalizedRel, normalizedBase).toString();
};

export type RetryPromptExecutionOptions = {
  promptSpecId: string;
  baseUrl?: string;
  /** Test seam — defaults to the global `fetch` in production. */
  fetchImpl?: typeof fetch;
};

export const retryPromptExecution = async ({
  promptSpecId,
  baseUrl,
  fetchImpl,
}: RetryPromptExecutionOptions): Promise<void> => {
  const fetcher = fetchImpl ?? globalThis.fetch;
  if (typeof fetcher !== 'function') {
    throw new Error('fetch is not available in this environment');
  }
  const resolvedBase = resolveGeneratedApiBaseUrl(baseUrl);
  const path = `/api/prompts/${encodeURIComponent(promptSpecId)}/execute/retry`;
  const url = resolvedBase ? joinUrl(resolvedBase, path) : path;
  const response = await fetcher(url, {
    method: 'POST',
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(
      `Retry execution failed (${response.status})${text ? `: ${text}` : ''}`,
    );
  }
};
