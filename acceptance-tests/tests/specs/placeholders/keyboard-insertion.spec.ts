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
 * Keyboard-path placeholder insertion spec for issue #228 — the
 * keyboard-activation counterpart to #217 (mouse path).
 *
 * Background: when a user focuses the message textarea, types, then
 * Tabs (or otherwise shifts focus by keyboard) to a placeholder row's
 * Insert button and activates it via Enter/Space, the caret-tracking
 * ref in `PromptFormShell` has just been cleared by the textarea's
 * `onBlur` handler in `components/ui/src/prompts-v2/form/sections.tsx`.
 * The insert handler therefore sees `selection === null` and short-
 * circuits with the "Place the cursor in the editor where the
 * placeholder should be inserted." hint instead of inserting.
 *
 * The fix is to drop the blur-time `null`-clear so the last known caret
 * position survives focus transitions. The insert helper at
 * `components/promptlm-web-ui/src/features/prompt-editor/
 * insertPlaceholderAtCaret.ts` already clamps the message-index, so a
 * stale-but-bounded ref is harmless.
 *
 * Both Enter and Space activate buttons per ARIA, so we cover each.
 *
 * Flow (per case):
 *  1. Pre-seed an active project so the editor bootstrap calls succeed.
 *  2. Open `/prompts/new`, fill the required fields with minimal values
 *     so the form is valid (we are not asserting save here).
 *  3. Add one placeholder (`kbd_test`).
 *  4. Focus the user-message textarea, type a prefix so the caret sits
 *     at position 7.
 *  5. Focus the Insert button directly (bypasses tab-order brittleness
 *     — the bug is about the focus *transition* clearing the ref, which
 *     `.focus()` reproduces).
 *  6. Press Enter (or Space) to activate the button.
 *  7. Assert the textarea contains `prefix {{kbd_test}}` — default
 *     delimiters from `draftState.ts` are `{{` / `}}`.
 */

import { test, expect } from '../../fixtures/backend';

/** Fixed-prefix UUID matching the placeholder spec seed style. */
const PROJECT_ID = '44444444-4444-4444-8444-444444444402';

const PROMPT_NAME = 'kbd-placeholder-insert';
const PROMPT_GROUP = 'kbd-group';
const PLACEHOLDER_NAME = 'kbd_test';

const seedFormBasics = async (page: import('@playwright/test').Page): Promise<void> => {
  await expect(page.getByTestId('prompt-editor-heading')).toBeVisible();
  await page.getByTestId('prompt-name-input').fill(PROMPT_NAME);
  await page.getByTestId('prompt-group-input').fill(PROMPT_GROUP);
  await page.getByTestId('description-text').fill('Keyboard insert path.');
  await page.getByTestId('request-vendor-select').selectOption('openai');
  await page.getByTestId('request-model-select').fill('gpt-4o-mini');
};

/**
 * Add a placeholder row and commit its `name`. Mirrors the helper in
 * `delimiters-and-defaults.spec.ts`. The Tab press is load-bearing: it
 * moves focus off the name input so React commits the row's name into
 * state, which then resolves the row's `placeholder-row-${name}` testid
 * and the insert button's `placeholder-insert-button-${name}` testid.
 */
const addPlaceholder = async (
  page: import('@playwright/test').Page,
  name: string,
): Promise<void> => {
  await page.getByTestId('placeholder-add-button').click();
  const nameInputs = page.locator("[data-testid^='placeholder-name-input-']");
  const last = nameInputs.last();
  await last.waitFor({ state: 'visible' });
  await last.fill(name);
  await last.press('Tab');
};

for (const activationKey of ['Enter', 'Space'] as const) {
  test(`keyboard ${activationKey} on Insert button inserts placeholder at caret`, async ({
    page,
    backend,
  }) => {
    await backend.seedProject({
      id: PROJECT_ID,
      name: 'Keyboard insert project',
    });

    await page.goto('/prompts/new');
    await seedFormBasics(page);
    await addPlaceholder(page, PLACEHOLDER_NAME);

    // Focus the user-message textarea and type a prefix. After the
    // `type` call the caret sits at the end of the typed text (position
    // 7 — six letters + trailing space). The `onSelect`/`onKeyUp`
    // handlers in `sections.tsx` push that selection into the shell's
    // `caretSelectionRef` via `onContentSelectionChange`.
    const textarea = page.getByTestId('prompt-text');
    await textarea.focus();
    await page.keyboard.type('prefix ');

    // Focus the Insert button directly. This deliberately bypasses the
    // tab-order: the bug is the focus *transition* clearing the ref via
    // the textarea's `onBlur`, which `.focus()` on another element
    // triggers identically to a real Tab press. Using `.focus()` keeps
    // the assertion robust against tab-order tweaks (rail re-orderings,
    // tabindex changes, etc.).
    const insertButton = page.getByTestId(
      `placeholder-insert-button-${PLACEHOLDER_NAME}`,
    );
    await insertButton.focus();
    await page.keyboard.press(activationKey);

    // Defaults from `draftState.ts`: startPattern `{{`, endPattern `}}`.
    // The caret was at position 7, so the token is inserted directly
    // after the trailing space.
    await expect(textarea).toHaveValue(`prefix {{${PLACEHOLDER_NAME}}}`);
  });
}
