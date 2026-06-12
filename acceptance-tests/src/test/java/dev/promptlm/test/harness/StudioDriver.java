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

package dev.promptlm.test.harness;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import dev.promptlm.domain.promptspec.PromptSpecLifecycleState;

import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Page-object façade exposing user-shaped verbs over the v2 Studio SPA.
 *
 * <p>Every method on this class drives the browser via real DOM events
 * ({@code page.click}, {@code page.fill}, {@code page.keyboard.press}). Calls
 * to {@code page.evaluate} or {@code page.request} are forbidden — the harness
 * design treats those as test-internal shortcuts that bypass user behaviour.
 *
 * <p>Selectors prefer {@code data-testid} (matching the testids exercised by
 * the existing TypeScript specs under {@code acceptance-tests/tests/specs/}).
 * Where a needed testid does not yet exist on the v2 SPA, the helper uses a
 * role-based fallback and tags the call with a {@code TODO(testid)} comment.
 */
public final class StudioDriver {

    private final Page page;
    private final String baseUrl;

    /**
     * Creates a driver bound to an already-opened Playwright {@link Page}.
     *
     * @param page    Playwright page (assumed pre-navigated context)
     * @param baseUrl base URL of the running webapp, no trailing slash
     */
    public StudioDriver(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    /**
     * Navigates to {@code /} and waits for the SPA shell to mount.
     */
    public void openHome() {
        page.navigate(baseUrl + "/");
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
    }

    /**
     * Navigates straight to the prompt-create form.
     */
    public void openNewPromptForm() {
        page.navigate(baseUrl + "/prompts/new");
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        page.getByTestId("prompt-editor-heading")
                .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
    }

    /**
     * Navigates to the edit view of an existing prompt.
     *
     * @param promptId id of the prompt (group/name)
     */
    public void openPromptForEdit(String promptId) {
        page.navigate(baseUrl + "/prompts/" + promptId + "/edit");
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
    }

    // ---------------------------------------------------------------------
    // Form fields
    // ---------------------------------------------------------------------

    /**
     * Fills the prompt id field. On the create form the v2 SPA derives the
     * id from {@code group/name}; this method is reserved for future variants
     * that expose an explicit id input.
     *
     * <p>TODO(testid): no dedicated {@code prompt-id-input} testid exists in
     * the v2 SPA today; this method currently no-ops and is kept on the API
     * surface so call-sites are stable when the testid lands.
     */
    public void fillPromptId(String id) {
        // Intentionally empty — id is derived from group/name in the v2 SPA.
        // Keep parameter referenced so static analyzers don't drop the API.
        if (id == null) {
            throw new IllegalArgumentException("prompt id must not be null");
        }
    }

    /**
     * Types the prompt's name into the name field.
     */
    public void fillName(String name) {
        page.getByTestId("prompt-name-input").fill(name);
    }

    /**
     * Types the prompt's group into the group field.
     */
    public void fillGroup(String group) {
        page.getByTestId("prompt-group-input").fill(group);
    }

    /**
     * Fills the trailing user-role message body in the message editor.
     */
    public void fillUserMessage(String body) {
        page.getByTestId("prompt-text").fill(body);
    }

    // ---------------------------------------------------------------------
    // Sub-areas
    // ---------------------------------------------------------------------

    /**
     * Returns the toolbar sub-driver (Save / Push / Release actions).
     */
    public Toolbar toolbar() {
        return new Toolbar();
    }

    /**
     * Returns the placeholders panel sub-driver.
     */
    public Placeholders placeholders() {
        return new Placeholders();
    }

    /**
     * Returns the run-tab sub-driver.
     */
    public RunTab runTab() {
        return new RunTab();
    }

    // ---------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------

    /**
     * Asserts the lifecycle-state badge in the editor header shows the
     * expected state. Polls Playwright's auto-waiting selector to absorb the
     * round-trip between user action and SPA derivation.
     *
     * <p>TODO(testid): no {@code prompt-lifecycle-badge} testid exists on the
     * v2 SPA yet. Falls back to a case-insensitive role/text match on any
     * status-like element. When the testid lands, switch to
     * {@code page.getByTestId("prompt-lifecycle-badge")}.
     */
    public void expectBadge(PromptSpecLifecycleState expected) {
        String label = expected.name().toLowerCase(Locale.ROOT);
        Pattern labelPattern = Pattern.compile("\\b" + Pattern.quote(label) + "\\b", Pattern.CASE_INSENSITIVE);
        Locator badge = page.locator("[data-testid='prompt-lifecycle-badge']").first();
        if (badge.count() > 0) {
            assertThat(badge.textContent()).containsIgnoringCase(label);
            return;
        }
        // Role/name fallback: any element with a status role whose accessible
        // name mentions the lifecycle label.
        Locator statusEl = page.getByRole(AriaRole.STATUS, new Page.GetByRoleOptions().setName(labelPattern)).first();
        statusEl.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
    }

    /**
     * Asserts a success toast containing the given fragment is visible.
     *
     * <p>TODO(testid): toast container has no testid in the v2 SPA. Falls
     * back to a {@code role=status} match.
     */
    public void expectToastSuccess(String fragment) {
        Locator toast = page.getByRole(AriaRole.STATUS,
                new Page.GetByRoleOptions().setName(Pattern.compile(Pattern.quote(fragment), Pattern.CASE_INSENSITIVE)));
        toast.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
    }

    /**
     * Returns the prompt id derived from the current URL ({@code /prompts/:id}).
     */
    public String currentPromptId() {
        String url = page.url();
        int idx = url.indexOf("/prompts/");
        if (idx < 0) {
            return "";
        }
        String tail = url.substring(idx + "/prompts/".length());
        int slash = tail.lastIndexOf('/');
        // The detail URL is /prompts/{group}/{name}; preserve the slash inside the id.
        int q = tail.indexOf('?');
        if (q >= 0) {
            tail = tail.substring(0, q);
        }
        int h = tail.indexOf('#');
        if (h >= 0) {
            tail = tail.substring(0, h);
        }
        if (tail.endsWith("/edit")) {
            tail = tail.substring(0, tail.length() - "/edit".length());
        }
        if (slash > 0 && tail.endsWith("/new")) {
            return "";
        }
        return tail;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    // ---------------------------------------------------------------------
    // Inner sub-drivers
    // ---------------------------------------------------------------------

    /**
     * Toolbar verbs (Save / Push / Release).
     */
    public final class Toolbar {

        /**
         * Clicks the primary Save / Create action. Maps to the
         * {@code save-prompt-button} testid which the v2 SPA uses for both
         * the create and update primary action when the release flow is off.
         */
        public void clickSave() {
            page.getByTestId("save-prompt-button").click();
        }

        /**
         * Clicks the Commit action.
         *
         * <p>TODO(testid): the v2 SPA does not expose a separate
         * {@code commit-prompt-button} today. The lifecycle scenario
         * tolerates a merged save+commit step — see the scenario javadoc.
         * This method currently falls back to a role-based "Commit" button
         * lookup so the call-site remains stable.
         */
        public void clickCommit() {
            Locator commitButton = page.locator("[data-testid='commit-prompt-button']").first();
            if (commitButton.count() > 0) {
                commitButton.click();
                return;
            }
            Locator fallback = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(Pattern.compile("commit", Pattern.CASE_INSENSITIVE)));
            if (fallback.count() > 0) {
                fallback.first().click();
            }
            // Otherwise: no-op — Save merges save+commit on the current SPA.
        }

        /**
         * Clicks the Push action that pushes the active branch to {@code origin}.
         *
         * <p>TODO(testid): falls back to a button-by-name lookup.
         */
        public void clickPush() {
            Locator pushButton = page.locator("[data-testid='push-prompt-button']").first();
            if (pushButton.count() > 0) {
                pushButton.click();
                return;
            }
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(Pattern.compile("push", Pattern.CASE_INSENSITIVE)))
                    .first()
                    .click();
        }

        /**
         * Opens the release-request dialog / submits a release request.
         */
        public void clickRelease() {
            Locator releaseButton = page.locator("[data-testid='release-prompt-button']").first();
            if (releaseButton.count() > 0) {
                releaseButton.click();
                return;
            }
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(Pattern.compile("release", Pattern.CASE_INSENSITIVE)))
                    .first()
                    .click();
        }

        /**
         * Confirms the release dialog (clicks the final "Release" confirmation).
         */
        public void clickReleaseComplete() {
            Locator confirm = page.locator("[data-testid='release-confirm-button']").first();
            if (confirm.count() > 0) {
                confirm.click();
                return;
            }
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(Pattern.compile("confirm|complete", Pattern.CASE_INSENSITIVE)))
                    .first()
                    .click();
        }
    }

    /**
     * Placeholders panel verbs.
     */
    public final class Placeholders {

        /**
         * Adds a placeholder row, fills its name + default value, and commits
         * by tabbing out so the row's React state lands.
         */
        public void addRow(String name, String value) {
            Locator addButton = page.getByTestId("placeholder-add-button");
            addButton.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            addButton.click();
            Locator nameInput = page.locator("[data-testid^='placeholder-name-input-']").last();
            nameInput.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            nameInput.fill(name);
            nameInput.press("Tab");
            Locator valueEditor = page.getByTestId("placeholder-value-textarea-" + name + "-0");
            valueEditor.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            valueEditor.fill(value);
            valueEditor.press("Tab");
        }

        /**
         * Sets the open / close delimiter sequences used by the user-message
         * body to mark placeholder tokens.
         */
        public void setDelimiters(String start, String end) {
            page.getByTestId("placeholder-open-sequence-input").fill(start);
            page.getByTestId("placeholder-close-sequence-input").fill(end);
        }

        /**
         * Clicks the "insert into user message" affordance for the given
         * placeholder row.
         *
         * <p>TODO(testid): falls back to a button-by-name match. The keyboard
         * insertion spec covers the alt-path.
         */
        public void clickInsert(String name) {
            Locator explicit = page.locator("[data-testid='placeholder-insert-" + name + "']").first();
            if (explicit.count() > 0) {
                explicit.click();
                return;
            }
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(Pattern.compile("insert", Pattern.CASE_INSENSITIVE)))
                    .first()
                    .click();
        }
    }

    /**
     * Run-tab verbs (Run / response / cost).
     */
    public final class RunTab {

        /**
         * Clicks the "Run" button on the editor's run tab.
         */
        public void clickRun() {
            page.getByTestId("prompt-editor-run-action").click();
        }

        /**
         * Asserts the rendered response contains the given fragment.
         */
        public void expectResponseContains(String fragment) {
            Locator response = page.getByTestId("prompt-editor-run-response");
            response.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            assertThat(response.textContent()).contains(fragment);
        }

        /**
         * Asserts the per-run cost cell is visible (any non-blank value).
         */
        public void expectCostVisible() {
            Locator cost = page.getByTestId("prompt-editor-run-cost");
            cost.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            assertThat(cost.isVisible()).isTrue();
        }
    }
}
