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

export type PromptStatus =
  | 'draft'
  | 'production'
  | 'staging'
  | 'experimental'
  | 'failing';

export interface StatusDotProps extends React.HTMLAttributes<HTMLSpanElement> {
  status: PromptStatus;
  /** Hide the text label and show only the dot. */
  iconOnly?: boolean;
}

const STATUS_MAP: Record<PromptStatus, { color: string; label: string }> = {
  draft: { color: 'var(--pl-ink-500)', label: 'draft' },
  production: { color: 'var(--pl-ok)', label: 'production' },
  staging: { color: 'var(--pl-warn)', label: 'staging' },
  experimental: { color: 'var(--pl-ink-500)', label: 'experimental' },
  failing: { color: 'var(--pl-fail)', label: 'failing' },
};

export const StatusDot = React.forwardRef<HTMLSpanElement, StatusDotProps>(function StatusDot(
  { status, iconOnly = false, style, ...rest },
  ref,
) {
  const s = STATUS_MAP[status];
  return (
    <span
      ref={ref}
      {...rest}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        fontFamily: 'var(--pl-mono)',
        fontSize: 11,
        color: 'var(--pl-ink-600)',
        letterSpacing: '0.01em',
        ...style,
      }}
    >
      <span
        style={{
          width: 6,
          height: 6,
          borderRadius: 999,
          background: s.color,
          boxShadow:
            status === 'production'
              ? `0 0 0 3px color-mix(in oklch, ${s.color} 18%, transparent)`
              : 'none',
        }}
      />
      {!iconOnly && s.label}
    </span>
  );
});
