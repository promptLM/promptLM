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

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import dev.promptlm.pricing.ModelPricingProperties.ModelPrice;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Computes USD cost for a single chat-completion execution from the configured per-model price
 * table (see {@link ModelPricingProperties}). The service is intentionally tiny — it has no
 * dependencies on the domain types so it stays usable from controllers, CLI flows, and any future
 * cost-reporting surfaces.
 *
 * <p>Lookup is case-insensitive and tolerates an optional vendor prefix
 * (e.g. {@code openai/gpt-4o}, {@code anthropic/claude-3-5-sonnet}). Unknown models return
 * {@link Optional#empty()}; the UI is expected to hide the cost chip in that case rather than
 * show a misleading {@code $0.00}.
 */
@Service
@EnableConfigurationProperties(ModelPricingProperties.class)
public class ModelPricingService {

    private final ModelPricingProperties properties;

    public ModelPricingService(ModelPricingProperties properties) {
        this.properties = properties;
    }

    /**
     * Compute the USD cost for the given token counts. Returns {@link Optional#empty()} when
     * the model is unknown, when both per-direction prices are missing, or when both token
     * counts are null/zero.
     */
    public Optional<Double> computeCost(String model, Integer tokensIn, Integer tokensOut) {

        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        ModelPrice price = lookup(model);
        if (price == null) {
            return Optional.empty();
        }
        int inTokens = tokensIn == null ? 0 : tokensIn;
        int outTokens = tokensOut == null ? 0 : tokensOut;
        if (inTokens <= 0 && outTokens <= 0) {
            return Optional.empty();
        }
        double cost = 0.0;
        boolean anyPriced = false;
        if (price.getInputPerMillion() != null && inTokens > 0) {
            cost += (inTokens / 1_000_000.0) * price.getInputPerMillion();
            anyPriced = true;
        }
        if (price.getOutputPerMillion() != null && outTokens > 0) {
            cost += (outTokens / 1_000_000.0) * price.getOutputPerMillion();
            anyPriced = true;
        }
        if (!anyPriced) {
            return Optional.empty();
        }
        return Optional.of(cost);
    }

    // Trailing dated-revision suffix used by OpenAI / Anthropic SDK exposed ids,
    // e.g. "gpt-5-2025-08-07", "claude-opus-4-5-20251101", "o3-2025-04-16".
    // Matches "-YYYY-MM-DD" or "-YYYYMMDD" anchored to end of string.
    private static final Pattern DATED_SUFFIX = Pattern.compile("-(\\d{4}-\\d{2}-\\d{2}|\\d{8})$");

    private ModelPrice lookup(String model) {

        Map<String, ModelPrice> table = properties.getModels();
        if (table.isEmpty()) {
            return null;
        }
        String trimmed = model.trim();
        ModelPrice direct = table.get(trimmed);
        if (direct != null) {
            return direct;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        ModelPrice match = matchCaseInsensitive(table, normalized);
        if (match != null) {
            return match;
        }
        // Tolerate vendor-prefixed forms like "openai/gpt-4o".
        int slash = normalized.indexOf('/');
        String afterVendor = normalized;
        if (slash >= 0 && slash < normalized.length() - 1) {
            afterVendor = normalized.substring(slash + 1);
            match = matchCaseInsensitive(table, afterVendor);
            if (match != null) {
                return match;
            }
        }
        // Tolerate dated SDK ids ("gpt-5-2025-08-07", "claude-opus-4-5-20251101",
        // "o3-2025-04-16") by stripping a trailing -YYYY-MM-DD or -YYYYMMDD and
        // retrying against the short-form catalog keys.
        String undated = DATED_SUFFIX.matcher(afterVendor).replaceFirst("");
        if (!undated.equals(afterVendor)) {
            match = matchCaseInsensitive(table, undated);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static ModelPrice matchCaseInsensitive(Map<String, ModelPrice> table, String needle) {
        for (Map.Entry<String, ModelPrice> entry : table.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(needle)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
