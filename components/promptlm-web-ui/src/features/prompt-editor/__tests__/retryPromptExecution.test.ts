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

import { describe, expect, it, vi } from 'vitest';

import { retryPromptExecution } from '../retryPromptExecution';

describe('retryPromptExecution', () => {
  it('POSTs to the retry endpoint with a URL-encoded prompt id', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 202 })) as unknown as typeof fetch;

    await retryPromptExecution({ promptSpecId: 'abc/123', fetchImpl, baseUrl: 'http://api.local' });

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const [url, init] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('http://api.local/api/prompts/abc%2F123/execute/retry');
    expect(init).toMatchObject({ method: 'POST' });
  });

  it('throws when the server returns a non-2xx status', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response('nope', { status: 500 }),
    ) as unknown as typeof fetch;

    await expect(
      retryPromptExecution({ promptSpecId: 'p', fetchImpl }),
    ).rejects.toThrow(/Retry execution failed \(500\)/);
  });
});
