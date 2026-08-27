package com.comet.opik.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Gemini thinking configuration decoded from {@code custom_parameters.thinking}, shared by the Google AI Studio and
 * Vertex AI providers.
 * <p>
 * The two providers do not expose the same knobs. AI Studio accepts a thinking <em>level</em> alongside a numeric
 * budget, while the Vertex {@code GenerationConfig.ThinkingConfig} protobuf only carries {@code thinking_budget} and
 * {@code include_thoughts}. A level therefore has to be translated into a budget for Vertex, which is what
 * {@link #budgetForLevel()} is for.
 */
public record GeminiThinkingParams(Level level, Integer budgetTokens, Boolean includeThoughts) {

    public static final GeminiThinkingParams ABSENT = new GeminiThinkingParams(null, null, null);

    /**
     * Thinking levels accepted by the Google AI Studio API, with the budget each one maps to on Vertex.
     * <p>
     * Google documents levels rather than budget numbers, so these budgets are Opik's own interpretation, spaced to
     * keep the ordering meaningful. {@code OFF} exists because Gemini 2.5 Flash Lite ships with thinking disabled and
     * an explicit zero budget is the only way to express "keep it off" once a level is being sent.
     */
    public enum Level {
        OFF(0),
        MINIMAL(512),
        LOW(2048),
        MEDIUM(8192),
        HIGH(24576);

        private final int budgetTokens;

        Level(int budgetTokens) {
            this.budgetTokens = budgetTokens;
        }

        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<Level> parse(String value) {
            return Arrays.stream(values())
                    .filter(level -> level.name().equalsIgnoreCase(value))
                    .findFirst();
        }
    }

    public boolean isAbsent() {
        return level == null && budgetTokens == null && includeThoughts == null;
    }

    /**
     * The budget to send to Vertex: an explicit budget wins over the level it would otherwise be derived from.
     */
    public Integer budgetForLevel() {
        if (budgetTokens != null) {
            return budgetTokens;
        }
        return level == null ? null : level.budgetTokens;
    }

    /**
     * Decodes the judge/online-evaluation shape, where custom parameters arrive as a {@link JsonNode}.
     */
    public static GeminiThinkingParams from(JsonNode customParameters) {
        if (customParameters == null || !customParameters.isObject()) {
            return ABSENT;
        }

        var thinking = customParameters.get("thinking");
        if (thinking == null || !thinking.isObject()) {
            return ABSENT;
        }

        return new GeminiThinkingParams(
                parseLevel(asText(thinking.get("level"))),
                parseBudget(thinking.get("budget_tokens")),
                asBoolean(thinking.get("include_thoughts")));
    }

    /**
     * Decodes the playground shape, where custom parameters arrive as a plain {@link Map} off the proxied request.
     */
    public static GeminiThinkingParams from(Map<String, Object> customParameters) {
        if (customParameters == null || !(customParameters.get("thinking") instanceof Map<?, ?> thinking)) {
            return ABSENT;
        }

        return new GeminiThinkingParams(
                parseLevel(thinking.get("level") instanceof String level ? level : null),
                parseBudget(thinking.get("budget_tokens")),
                thinking.get("include_thoughts") instanceof Boolean includeThoughts ? includeThoughts : null);
    }

    private static Level parseLevel(String value) {
        return StringUtils.isBlank(value) ? null : Level.parse(value).orElse(null);
    }

    // A budget of 0 is meaningful — it disables thinking — so only negative values are rejected. -1 requests dynamic
    // thinking on Google's side, but langchain4j forwards budgets verbatim and Opik has no UI for it, so it is not
    // accepted here rather than being silently coerced.
    // isIntegralNumber() rather than canConvertToInt() alone: the latter is true for floating-point values, which
    // would silently truncate a budget of 1.5 to 1 instead of rejecting it.
    private static Integer parseBudget(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= 0
                ? node.asInt()
                : null;
    }

    private static Integer parseBudget(Object value) {
        if (!(value instanceof Integer || value instanceof Long || value instanceof Short)) {
            return null;
        }

        long budget = ((Number) value).longValue();
        return budget >= 0 && budget <= Integer.MAX_VALUE ? (int) budget : null;
    }

    private static String asText(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static Boolean asBoolean(JsonNode node) {
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }
}
