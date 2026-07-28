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

package dev.promptlm.test.support;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable Playwright interactions for the prompt editor — used by both
 * the full happy-path journey and the native-binary UI smoke test.
 */
public final class PromptWorkflowHelper {

    private PromptWorkflowHelper() {
    }

    /**
     * Fills the prompt editor form and clicks Save. Waits for the URL to transition off
     * {@code /prompts/new} so callers know the create round-tripped through the backend.
     */
    public static void createPromptViaForm(Page page, PromptCreationInput input) {
        page.getByTestId("create-prompt-button").click();

        Locator editorHeading = page.getByTestId("prompt-editor-heading");
        editorHeading.waitFor();
        assertThat(editorHeading.isVisible()).isTrue();

        page.getByTestId("prompt-name-input").fill(input.name());
        page.getByTestId("prompt-group-input").fill(input.group());
        page.getByTestId("description-text").fill(input.description());

        // Select a vendor + model. Required by validateModelConfiguration
        // (promptlm-web-ui/src/features/prompt-editor/validation.ts) — the
        // backend's default template returns both blank since #309, so Save
        // stays disabled otherwise.
        //
        // openai specifically: saving triggers a real LLM execution and the
        // push to the remote is chained to that execution succeeding. The
        // acceptance suite authenticates with OPENAI_API_KEY, so any other
        // vendor 401s and the spec never leaves the working tree.
        page.getByTestId("request-vendor-select").selectOption("openai");
        page.getByTestId("request-model-select").fill("gpt-4o-mini");

        // The default new-prompt draft seeds two messages — {role:'system'}
        // then {role:'user'} — both empty. validateMessages rejects any
        // empty content. Fill both up front; the `user-prompt-button` click
        // below *adds a THIRD* message that the existing test then types
        // into, so without seeding these two we'd end up with an empty
        // middle message and a stuck "! Empty." error on Save. Targeted
        // by aria-label "Message content N" (1-indexed) — neither the
        // system row nor the middle user row has a dedicated testid.
        page.getByLabel("Message content 1").fill(
                "System seed for " + input.name() + " (required by validateMessages).");
        page.getByLabel("Message content 2").fill(
                "Default-user seed for " + input.name() + " (required by validateMessages).");

        configureCustomPlaceholderDelimiters(page, input.openDelimiter(), input.closeDelimiter());

        for (Map.Entry<String, String> placeholder : input.placeholderDefaults().entrySet()) {
            addPlaceholder(page, placeholder.getKey());
            setPlaceholderValue(page, placeholder.getKey(), placeholder.getValue());
        }

        page.getByTestId("user-prompt-button").click();
        Locator promptTextarea = page.getByTestId("prompt-messages").locator("textarea").last();
        // Use keystroke-level typing rather than .fill() so the React shell observes the same
        // event sequence it gets from a real user. .fill() dispatches a single input event with
        // the full value, which can bypass token-detection handlers the editor relies on to
        // recognise placeholder delimiters like [[topic]] as live tokens.
        promptTextarea.click();
        promptTextarea.fill("");
        page.keyboard().type(input.userMessage());

        page.getByTestId("save-prompt-button").click();

        // After a successful create, the v2 shell navigates from /prompts/new to
        // the new detail page (/prompts/:id). Wait for that URL transition rather
        // than the transient "Prompt created" toast.
        page.waitForURL(
                url -> {
                    if (url == null) {
                        return false;
                    }
                    int promptsIdx = url.indexOf("/prompts/");
                    if (promptsIdx < 0) {
                        return false;
                    }
                    String tail = url.substring(promptsIdx + "/prompts/".length());
                    int slash = tail.indexOf('/');
                    String segment = slash < 0 ? tail : tail.substring(0, slash);
                    int q = segment.indexOf('?');
                    if (q >= 0) {
                        segment = segment.substring(0, q);
                    }
                    int h = segment.indexOf('#');
                    if (h >= 0) {
                        segment = segment.substring(0, h);
                    }
                    return !segment.isEmpty() && !"new".equals(segment);
                },
                new Page.WaitForURLOptions().setTimeout(60_000));
    }

    /**
     * Clears required fields on the prompt-new form and asserts the inline error
     * indicators are visible and the save button is disabled.
     */
    public static void assertRequiredFieldValidation(Page page) {
        page.getByTestId("prompt-name-input").fill("");
        page.getByTestId("prompt-group-input").fill("");
        // Move focus off the cleared fields so the form's error-count derivation
        // catches up before we assert visibility.
        page.keyboard().press("Tab");

        page.waitForSelector("[data-testid='prompt-name-error']",
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
        page.waitForSelector("[data-testid='prompt-group-error']",
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));

        assertThat(page.isVisible("[data-testid='prompt-name-error']")).isTrue();
        assertThat(page.isVisible("[data-testid='prompt-group-error']")).isTrue();
        assertThat(page.getByTestId("save-prompt-button").isDisabled()).isTrue();
    }

    private static void configureCustomPlaceholderDelimiters(Page page, String openSequence, String closeSequence) {
        Locator openInput = page.getByTestId("placeholder-open-sequence-input");
        Locator closeInput = page.getByTestId("placeholder-close-sequence-input");
        openInput.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        closeInput.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        openInput.fill(openSequence);
        closeInput.fill(closeSequence);
    }

    private static void addPlaceholder(Page page, String name) {
        Locator addButton = page.getByTestId("placeholder-add-button");
        addButton.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        addButton.click();
        Locator nameInput = page.locator("[data-testid^='placeholder-name-input-']").last();
        nameInput.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        nameInput.fill(name);
        nameInput.press("Tab");
    }

    private static void setPlaceholderValue(Page page, String placeholderName, String value) {
        Locator valueEditor = page.getByTestId("placeholder-value-textarea-" + placeholderName + "-0");
        valueEditor.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        valueEditor.fill(value);
        valueEditor.press("Tab");
    }

    /**
     * Parameters for {@link #createPromptViaForm(Page, PromptCreationInput)}.
     * Use {@link #builder()} to construct.
     */
    public record PromptCreationInput(
            String name,
            String group,
            String description,
            String openDelimiter,
            String closeDelimiter,
            Map<String, String> placeholderDefaults,
            String userMessage
    ) {
        public PromptCreationInput {
            placeholderDefaults = Map.copyOf(placeholderDefaults);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String name;
            private String group;
            private String description = "";
            private String openDelimiter = "[[";
            private String closeDelimiter = "]]";
            private final Map<String, String> placeholderDefaults = new LinkedHashMap<>();
            private String userMessage = "";

            public Builder name(String value) { this.name = value; return this; }
            public Builder group(String value) { this.group = value; return this; }
            public Builder description(String value) { this.description = value; return this; }
            public Builder delimiters(String open, String close) {
                this.openDelimiter = open;
                this.closeDelimiter = close;
                return this;
            }
            public Builder placeholder(String name, String defaultValue) {
                this.placeholderDefaults.put(name, defaultValue);
                return this;
            }
            public Builder userMessage(String value) { this.userMessage = value; return this; }

            public PromptCreationInput build() {
                return new PromptCreationInput(name, group, description, openDelimiter, closeDelimiter,
                        placeholderDefaults, userMessage);
            }
        }
    }
}
