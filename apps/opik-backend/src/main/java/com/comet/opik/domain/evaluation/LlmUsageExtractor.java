package com.comet.opik.domain.evaluation;

import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import lombok.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a langchain4j {@link ChatResponse}'s token usage into the flat {@code Map<String, Integer>}
 * shape that {@link com.comet.opik.domain.cost.CostService#calculateCost} and the span usage field
 * expect. Shared by {@link EvaluationEntityFactory} (monitoring spans) and
 * {@link com.comet.opik.api.resources.v1.events.BudgetGuard} (in-loop cost accounting) so the
 * prompt-cache-aware key mapping lives in exactly one place.
 */
public final class LlmUsageExtractor {

    private LlmUsageExtractor() {
    }

    /**
     * Returns null when the response carries no usage, so callers can skip costing entirely.
     * <p>
     * Prompt/completion/total tokens are read from the generic {@link TokenUsage} interface, so they
     * are captured for <b>every</b> provider Opik supports (Anthropic, OpenAI chat + Responses,
     * Gemini, Vertex AI, and the OpenAI-compatible providers — OpenRouter, Ollama, custom-llm, free —
     * which all return either {@link OpenAiTokenUsage} or the base {@link TokenUsage}). The
     * per-provider switch below only <i>adds</i> the prompt-cache token counts, which each SDK exposes
     * under a different subtype.
     */
    public static Map<String, Integer> toUsageMap(@NonNull ChatResponse response) {
        var tokenUsage = response.tokenUsage();
        if (tokenUsage == null) {
            return null;
        }
        var usage = new HashMap<String, Integer>();
        if (tokenUsage.inputTokenCount() != null) {
            usage.put("prompt_tokens", tokenUsage.inputTokenCount());
        }
        if (tokenUsage.outputTokenCount() != null) {
            usage.put("completion_tokens", tokenUsage.outputTokenCount());
        }
        if (tokenUsage.totalTokenCount() != null) {
            usage.put("total_tokens", tokenUsage.totalTokenCount());
        }
        addCacheTokens(tokenUsage, usage);
        return usage.isEmpty() ? null : usage;
    }

    /**
     * Records provider-reported prompt-cache token counts under the keys
     * {@link com.comet.opik.domain.cost.SpanCostCalculator} prices: cache-read tokens are billed at a
     * reduced rate and cache-creation tokens at a surcharge. No-op for providers/responses without
     * cache usage, so cost is unchanged when caching is off (the current default).
     */
    private static void addCacheTokens(TokenUsage tokenUsage, Map<String, Integer> usage) {
        switch (tokenUsage) {
            case AnthropicTokenUsage anthropic -> {
                putPositive(usage, "cache_creation_input_tokens", anthropic.cacheCreationInputTokens());
                putPositive(usage, "cache_read_input_tokens", anthropic.cacheReadInputTokens());
            }
            // OpenAI-family providers (OpenAI, OpenRouter, custom-llm, Ollama, free) all return this type.
            case OpenAiTokenUsage openai -> {
                if (openai.inputTokensDetails() != null) {
                    putPositive(usage, "cache_read_input_tokens", openai.inputTokensDetails().cachedTokens());
                }
            }
            // OpenAI Responses API path (OpenAiOfficialResponsesChatModel) reports its own subtype.
            case OpenAiOfficialTokenUsage official -> {
                if (official.inputTokensDetails() != null) {
                    putPositive(usage, "cache_read_input_tokens", official.inputTokensDetails().cachedTokens());
                }
            }
            // Gemini reports the context-cache hit as cachedContentTokenCount, which the Google cost
            // path subtracts from prompt_tokens and prices at the cache-read rate.
            case GoogleAiGeminiTokenUsage gemini ->
                putPositive(usage, "cache_read_input_tokens", gemini.cachedContentTokenCount());
            default -> {
            }
        }
    }

    private static void putPositive(Map<String, Integer> usage, String key, Integer value) {
        if (value != null && value > 0) {
            usage.put(key, value);
        }
    }
}
