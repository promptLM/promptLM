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

package dev.promptlm.pricing;

import java.util.Map;
import java.util.Optional;

import dev.promptlm.pricing.ModelPricingProperties.ModelPrice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ModelPricingServiceTest {

    @Test
    void computeCost_combinesInputAndOutputPricing() {
        ModelPricingService service = serviceWith(Map.of(
                "gpt-4o", price(2.50, 10.00)
        ));

        Optional<Double> cost = service.computeCost("gpt-4o", 1_000, 200);

        assertThat(cost).isPresent();
        // 1_000 * 2.50 / 1_000_000 = 0.0025 ; 200 * 10.00 / 1_000_000 = 0.002
        assertThat(cost.get()).isCloseTo(0.0045, within(1e-9));
    }

    @Test
    void computeCost_returnsEmptyForUnknownModel() {
        ModelPricingService service = serviceWith(Map.of(
                "gpt-4o", price(2.50, 10.00)
        ));

        assertThat(service.computeCost("ollama-llama3", 500, 500)).isEmpty();
    }

    @Test
    void computeCost_returnsEmptyForBlankModel() {
        ModelPricingService service = serviceWith(Map.of(
                "gpt-4o", price(2.50, 10.00)
        ));

        assertThat(service.computeCost("", 500, 500)).isEmpty();
        assertThat(service.computeCost(null, 500, 500)).isEmpty();
    }

    @Test
    void computeCost_returnsEmptyWhenNoTokens() {
        ModelPricingService service = serviceWith(Map.of(
                "gpt-4o", price(2.50, 10.00)
        ));

        assertThat(service.computeCost("gpt-4o", 0, 0)).isEmpty();
        assertThat(service.computeCost("gpt-4o", null, null)).isEmpty();
    }

    @Test
    void computeCost_matchesCaseInsensitively() {
        ModelPricingService service = serviceWith(Map.of(
                "GPT-4o", price(2.50, 10.00)
        ));

        Optional<Double> cost = service.computeCost("gpt-4o", 1_000_000, 0);

        assertThat(cost).isPresent();
        assertThat(cost.get()).isCloseTo(2.50, within(1e-9));
    }

    @Test
    void computeCost_acceptsVendorPrefixedModel() {
        ModelPricingService service = serviceWith(Map.of(
                "claude-3-5-sonnet", price(3.00, 15.00)
        ));

        Optional<Double> cost = service.computeCost("anthropic/claude-3-5-sonnet", 1_000_000, 0);

        assertThat(cost).isPresent();
        assertThat(cost.get()).isCloseTo(3.00, within(1e-9));
    }

    @Test
    void computeCost_pricesOnlyConfiguredDirection() {
        // Only output pricing is configured — input contributes nothing but the
        // model is still considered priced.
        ModelPricingService service = serviceWith(Map.of(
                "claude-haiku", outputOnly(1.25)
        ));

        Optional<Double> cost = service.computeCost("claude-haiku", 10_000, 1_000);

        assertThat(cost).isPresent();
        assertThat(cost.get()).isCloseTo(0.00125, within(1e-9));
    }

    @Test
    void computeCost_matchesDatedOpenAiModelIdAgainstShortFormKey() {
        // promptLM/promptlm-app#229 — SDKs persist dated ids like
        // "gpt-5-2025-08-07"; the catalog keys are short-form ("gpt-5").
        ModelPricingService service = serviceWith(Map.of(
                "gpt-5", price(2.50, 10.00)
        ));

        Optional<Double> cost = service.computeCost("gpt-5-2025-08-07", 1_000_000, 0);

        assertThat(cost).isPresent();
        assertThat(cost.get()).isCloseTo(2.50, within(1e-9));
    }

    @Test
    void computeCost_matchesDatedAnthropicModelIdAgainstShortFormKey() {
        // Anthropic's dated form uses YYYYMMDD ("claude-opus-4-5-20251101").
        ModelPricingService service = serviceWith(Map.of(
                "claude-opus-4-5", price(15.00, 75.00)
        ));

        Optional<Double> cost = service.computeCost("claude-opus-4-5-20251101", 1_000_000, 0);

        assertThat(cost).isPresent();
        assertThat(cost.get()).isCloseTo(15.00, within(1e-9));
    }

    @Test
    void computeCost_matchesDatedReasoningModelIdAgainstShortFormKey() {
        // Regression for the o3-2025-04-16 case previously papered over in #224.
        ModelPricingService service = serviceWith(Map.of(
                "o3", price(2.00, 8.00)
        ));

        Optional<Double> cost = service.computeCost("o3-2025-04-16", 1_000_000, 0);

        assertThat(cost).isPresent();
        assertThat(cost.get()).isCloseTo(2.00, within(1e-9));
    }

    @Test
    void computeCost_matchesVendorPrefixedDatedId() {
        // Vendor prefix + dated suffix combined.
        ModelPricingService service = serviceWith(Map.of(
                "gpt-5", price(2.50, 10.00)
        ));

        Optional<Double> cost = service.computeCost("openai/gpt-5-2025-08-07", 1_000_000, 0);

        assertThat(cost).isPresent();
        assertThat(cost.get()).isCloseTo(2.50, within(1e-9));
    }

    @Test
    void computeCost_preservesExactMatchOverDatedFallback() {
        // If both the dated form and the short form are in the catalog,
        // the dated form must win — never silently shadow a more-specific
        // price with the short-form fallback.
        ModelPricingService service = serviceWith(Map.of(
                "gpt-5", price(2.50, 10.00),
                "gpt-5-2025-08-07", price(3.00, 12.00)
        ));

        Optional<Double> cost = service.computeCost("gpt-5-2025-08-07", 1_000_000, 0);

        assertThat(cost).isPresent();
        assertThat(cost.get()).isCloseTo(3.00, within(1e-9));
    }

    @Test
    void computeCost_emptyTableReturnsEmpty() {
        ModelPricingService service = serviceWith(Map.of());

        assertThat(service.computeCost("gpt-4o", 1, 1)).isEmpty();
    }

    private static ModelPricingService serviceWith(Map<String, ModelPrice> models) {
        ModelPricingProperties properties = new ModelPricingProperties();
        properties.setModels(new java.util.LinkedHashMap<>(models));
        return new ModelPricingService(properties);
    }

    private static ModelPrice price(double in, double out) {
        ModelPrice p = new ModelPrice();
        p.setInputPerMillion(in);
        p.setOutputPerMillion(out);
        return p;
    }

    private static ModelPrice outputOnly(double out) {
        ModelPrice p = new ModelPrice();
        p.setOutputPerMillion(out);
        return p;
    }
}
