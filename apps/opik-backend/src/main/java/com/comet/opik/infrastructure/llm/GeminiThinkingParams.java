package com.comet.opik.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Gemini thinking configuration decoded from {@code custom_parameters.thinking}, shared by the Google AI Studio and
 * Vertex AI providers.
 * <p>
 * A level reaches the wire as a level only on AI Studio with Gemini 3 or later. Everything else takes the budget it
 * translates to, which is what {@link #budgetForLevel()} is for:
 * <ul>
 * <li>Vertex, at any version — its {@code GenerationConfig.ThinkingConfig} protobuf carries only
 * {@code thinking_budget} and {@code include_thoughts}, with no level field at all.</li>
 * <li>Gemini 2.5 on either provider — {@code thinking_level} is Gemini 3+ only and earlier models reject it
 * outright, so 2.5 is level-driven in the UI but budget-driven on the wire.</li>
 * </ul>
 */
public record GeminiThinkingParams(Level level, Integer budgetTokens, Boolean includeThoughts) {

    public static final GeminiThinkingParams ABSENT = new GeminiThinkingParams(null, null, null);

    // Bounded, and required to be a real version token (followed by "." or "-"). The judge path takes
    // the model name as free text from the API: an unbounded group would overflow Integer.parseInt,
    // and a merely bounded one would read "gemini-99999999999-flash" as version 999.
    private static final Pattern GEMINI_MAJOR_VERSION = Pattern.compile("gemini-(\\d{1,3})(?=[.-])");

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

    /**
     * Whether a model takes {@code thinking_level} rather than the legacy {@code thinking_budget}.
     * <p>
     * Only Gemini 3 and later do: "If you use the thinking_level parameter with a model earlier than Gemini 3, the
     * model returns an error." Gemini 2.5 is level-capable in the product sense — the UI offers levels for it — but on
     * the wire a level has to be translated into a budget, exactly as it is for Vertex.
     * <p>
     * Matched on the model id rather than an allowlist so a newly synced Gemini 3+ model is not silently treated as
     * 2.5. Ids look like {@code gemini-3.7-flash} or {@code vertex_ai/gemini-2.5-pro}, so the major version is the
     * digits following the first {@code gemini-} in the id.
     */
    public static boolean modelAcceptsLevel(String model) {
        if (StringUtils.isBlank(model)) {
            return false;
        }

        var matcher = GEMINI_MAJOR_VERSION.matcher(model);
        return matcher.find() && Integer.parseInt(matcher.group(1)) >= 3;
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
