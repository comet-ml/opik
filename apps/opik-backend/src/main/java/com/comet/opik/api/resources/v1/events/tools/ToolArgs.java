package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

/**
 * Static helpers shared across {@link ReadTool}, {@link JqTool}, and
 * {@link SearchTool}: argument JSON parsing primitives and the canonical
 * cache-miss hint. Kept deliberately narrow — per-tool {@code parseArgs} flows
 * and header builders stay in their tool classes since required fields and
 * header layouts differ. Output truncation lives in {@link StringTruncator}.
 */
@UtilityClass
public final class ToolArgs {

    /** Wraps {@code message} as the {@code {"error": "..."}} JSON envelope tools return. */
    public static String errorJson(String message) {
        return "{\"error\": %s}".formatted(JsonUtils.writeValueAsString(message));
    }

    /** Returns the text value of {@code n}, or {@code null} when absent / null-valued. */
    public static String textOrNull(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    /** Standard cache-miss hint used by jq and search to nudge the agent toward {@code read}. */
    public static String cacheMiss(EntityType type, String id) {
        return "Entity (type=%s, id=%s) not in cache. Call read first."
                .formatted(type.name().toLowerCase(), id);
    }

    /**
     * Parses, validates, and returns the {@code type} field. Rejects
     * {@code thread} since no tool currently caches thread entities. The tool
     * name is interpolated into the rejection message, so the LLM sees which
     * tool refused.
     */
    public static Result<EntityType> parseType(JsonNode root, String toolName) {
        String typeStr = textOrNull(root.get("type"));
        if (typeStr == null || typeStr.isBlank()) {
            return Result.error(errorJson("Missing required argument: type"));
        }
        EntityType type;
        try {
            type = EntityType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Result.error(errorJson("Unknown type: " + typeStr));
        }
        if (type == EntityType.THREAD) {
            return Result.error(errorJson("type=thread is not supported by the " + toolName + " tool"));
        }
        return Result.ok(type);
    }

    /**
     * Reads a required string field. Returns an error result if missing or
     * blank, with the canonical {@code "Missing required argument: <name>"}
     * message.
     */
    public static Result<String> requireString(JsonNode root, String fieldName) {
        String value = textOrNull(root.get(fieldName));
        if (value == null || value.isBlank()) {
            return Result.error(errorJson("Missing required argument: " + fieldName));
        }
        return Result.ok(value);
    }

    /**
     * Either-style carrier for a parsed value or an error envelope. Exactly
     * one of {@code value} / {@code error} is non-null.
     */
    public record Result<T>(T value, String error) {
        public static <T> Result<T> ok(T value) {
            return new Result<>(value, null);
        }

        public static <T> Result<T> error(String error) {
            return new Result<>(null, error);
        }

        public boolean isError() {
            return error != null;
        }
    }
}
