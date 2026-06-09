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

import * as React from 'react';
import { MetaPill, Mono, type PromptStatus, type VendorId } from '../atoms';

export interface PreReleaseExecutionBadge {
  /** Status of the pre-release execution that gated this version. */
  status: 'ok' | 'error' | 'pending';
  /** Click handler — typically jumps to the execution row in the table below. */
  onClick: () => void;
  /** Tooltip text shown on hover (e.g. "View pre-release run exec-103"). */
  tooltip?: string;
}

export interface PromptDetailHeaderProps {
  name: string;
  description?: string;
  group: string;
  version: string;
  rev: string;
  vendor: VendorId;
  model: string;
  status: PromptStatus;
  /** Eyebrow label above the title (defaults to "Prompt"). */
  eyebrow?: string;
  /** Trailing meta line for the eyebrow row, e.g. "at HEAD" or "main · 3f7c2e1". */
  eyebrowMeta?: string;
  /**
   * When provided, renders the pre-release execution badge adjacent to the
   * version chip. Issue #98 — only released revisions surface this badge.
   */
  preReleaseExecution?: PreReleaseExecutionBadge;
}

const PRE_RELEASE_DOT: Record<PreReleaseExecutionBadge['status'], string> = {
  ok: 'var(--pl-ok)',
  error: 'var(--pl-fail)',
  pending: 'var(--pl-warn)',
};

const PreReleaseBadge: React.FC<{ badge: PreReleaseExecutionBadge }> = ({ badge }) => (
  <button
    type="button"
    data-testid="prompt-detail-pre-release-badge"
    data-status={badge.status}
    onClick={badge.onClick}
    title={badge.tooltip ?? 'View pre-release execution'}
    style={{
      display: 'inline-flex',
      alignItems: 'center',
      gap: 6,
      padding: '3px 8px',
      background: 'transparent',
      border: '1px solid var(--pl-ink-200)',
      borderRadius: 999,
      cursor: 'pointer',
      fontFamily: 'var(--pl-mono)',
      fontSize: 11,
      color: 'var(--pl-ink-700)',
    }}
  >
    <span
      aria-hidden="true"
      style={{
        width: 6,
        height: 6,
        borderRadius: 999,
        background: PRE_RELEASE_DOT[badge.status],
      }}
    />
    <span>pre-release run</span>
  </button>
);

const STATUS_FG: Record<PromptStatus, string> = {
  draft: 'var(--pl-ink-600)',
  production: 'oklch(0.40 0.12 155)',
  staging: 'oklch(0.50 0.13 75)',
  experimental: 'var(--pl-ink-600)',
  failing: 'oklch(0.45 0.13 25)',
};

const STATUS_DOT: Record<PromptStatus, string> = {
  draft: 'var(--pl-ink-500)',
  production: 'oklch(0.55 0.13 155)',
  staging: 'var(--pl-warn)',
  experimental: 'var(--pl-ink-500)',
  failing: 'var(--pl-fail)',
};

export const PromptDetailHeader: React.FC<PromptDetailHeaderProps> = ({
  name,
  description,
  group,
  version,
  rev,
  vendor,
  model,
  status,
  eyebrow = 'Prompt',
  eyebrowMeta = 'at HEAD',
  preReleaseExecution,
}) => (
  <div style={{ padding: '40px 56px 24px' }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
      <Mono
        size={11}
        color="var(--pl-signal-deep)"
        style={{ letterSpacing: '0.14em', textTransform: 'uppercase', fontWeight: 500 }}
      >
        {eyebrow}
      </Mono>
      <Mono size={11} color="var(--pl-ink-500)" style={{ letterSpacing: '0.06em' }}>
        group · {group}
      </Mono>
      <span style={{ width: 1, height: 10, background: 'var(--pl-ink-300)' }} />
      <Mono size={11} color="var(--pl-ink-500)">
        {eyebrowMeta}
      </Mono>
    </div>
    <h1
      style={{
        margin: 0,
        fontFamily: 'var(--pl-display)',
        fontSize: 44,
        fontWeight: 500,
        letterSpacing: '-0.02em',
        lineHeight: 1.05,
        color: 'var(--pl-ink-900)',
      }}
    >
      {name}
    </h1>
    {description && (
      <p
        style={{
          margin: '14px 0 0',
          fontSize: 15.5,
          lineHeight: 1.55,
          color: 'var(--pl-ink-600)',
          maxWidth: 720,
        }}
      >
        {description}
      </p>
    )}

    <div
      style={{ display: 'flex', alignItems: 'center', gap: 18, marginTop: 22, flexWrap: 'wrap' }}
    >
      <MetaPill label="version" value={version} mono />
      {preReleaseExecution ? <PreReleaseBadge badge={preReleaseExecution} /> : null}
      <MetaPill label="rev" value={rev} mono />
      <MetaPill label="model" value={`${vendor}/${model}`} mono />
      <MetaPill label="status">
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <span
            aria-hidden="true"
            style={{
              width: 6,
              height: 6,
              borderRadius: 999,
              background: STATUS_DOT[status],
            }}
          />
          <span
            style={{
              fontFamily: 'var(--pl-mono)',
              fontSize: 12,
              color: STATUS_FG[status],
            }}
          >
            {status}
          </span>
        </span>
      </MetaPill>
    </div>
  </div>
);
