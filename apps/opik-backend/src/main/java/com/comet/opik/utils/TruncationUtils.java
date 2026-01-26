package com.comet.opik.utils;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.UncheckedIOException;

/**
 * Utility class for handling data truncation operations in DAO classes.
 * Contains shared methods for JSON node processing and truncation threshold binding.
 */
@Slf4j
@UtilityClass
public final class TruncationUtils {

    /**
     * Default maximum length for truncated string values in slim JSON.
     */
    public static final int DEFAULT_SLIM_STRING_MAX_LENGTH = 1000;

    /**
     * Suffix appended to truncated strings to indicate truncation.
     */
    private static final String TRUNCATION_SUFFIX = "...";

    /**
     * Creates a "slim" version of a JSON node by recursively truncating all leaf string values
     * while preserving the complete JSON structure (all keys and nesting).
     *
     * <p>This is useful for:
     * <ul>
     *   <li>Displaying JSON in tables where full values aren't needed</li>
     *   <li>Autocomplete/dropdown population where only keys matter</li>
     *   <li>Reducing payload sizes while maintaining structure for column mapping</li>
     * </ul>
     *
     * <p>Behavior by node type:
     * <ul>
     *   <li><b>TextNode (strings):</b> Truncated to maxLength with "..." suffix if exceeded</li>
     *   <li><b>ObjectNode:</b> Recursively processes all values, preserving all keys</li>
     *   <li><b>ArrayNode:</b> Recursively processes all elements</li>
     *   <li><b>Numbers, booleans, nulls:</b> Passed through unchanged</li>
     * </ul>
     *
     * @param node the JSON node to create a slim version of (may be null)
     * @param maxStringLength maximum length for string values (excluding suffix)
     * @return a new JsonNode with truncated strings, or null if input was null
     */
    public static JsonNode createSlimJson(JsonNode node, int maxStringLength) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }

        if (node.isTextual()) {
            return truncateTextNode(node.asText(), maxStringLength);
        }

        if (node.isObject()) {
            ObjectNode result = JsonUtils.createObjectNode();
            node.properties()
                    .forEach(entry -> result.set(entry.getKey(), createSlimJson(entry.getValue(), maxStringLength)));
            return result;
        }

        if (node.isArray()) {
            ArrayNode result = JsonUtils.createArrayNode();
            node.forEach(element -> result.add(createSlimJson(element, maxStringLength)));
            return result;
        }

        // Numbers, booleans, etc. pass through unchanged
        return node;
    }

    /**
     * Creates a slim JSON string from the given JSON string input.
     * Falls back to simple substring truncation if the input is not valid JSON.
     *
     * @param jsonString the JSON string to process (may be null or empty)
     * @param maxStringLength maximum length for leaf string values
     * @return the slim JSON string, or the original/truncated string if not valid JSON
     */
    public static String createSlimJsonString(String jsonString, int maxStringLength) {
        if (jsonString == null || jsonString.isEmpty()) {
            return jsonString;
        }

        try {
            JsonNode node = JsonUtils.getJsonNodeFromString(jsonString);
            JsonNode slimNode = createSlimJson(node, maxStringLength);
            return JsonUtils.writeValueAsString(slimNode);
        } catch (UncheckedIOException e) {
            // Not valid JSON, fall back to simple truncation
            log.debug("Input is not valid JSON, falling back to simple truncation: {}", e.getMessage());
            return truncateString(jsonString, maxStringLength);
        }
    }

    /**
     * Creates a slim JSON string using the default max length.
     *
     * @param jsonString the JSON string to process
     * @return the slim JSON string
     * @see #createSlimJsonString(String, int)
     */
    public static String createSlimJsonString(String jsonString) {
        return createSlimJsonString(jsonString, DEFAULT_SLIM_STRING_MAX_LENGTH);
    }

    /**
     * Creates a slim JSON node from a JsonNode using the default max length.
     *
     * @param node the JSON node to process
     * @return the slim JSON node
     */
    public static JsonNode createSlimJson(JsonNode node) {
        return createSlimJson(node, DEFAULT_SLIM_STRING_MAX_LENGTH);
    }

    private static TextNode truncateTextNode(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return TextNode.valueOf(text);
        }
        return TextNode.valueOf(text.substring(0, maxLength) + TRUNCATION_SUFFIX);
    }

    private static String truncateString(@NonNull String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + TRUNCATION_SUFFIX;
    }

    /**
     * Returns a JsonNode from the given value, or a TextNode if the data is truncated.
     *
     * @param rowMetadata the row metadata to check for truncation flags
     * @param truncatedFlag the column name indicating if data is truncated
     * @param row the database row
     * @param value the string value to process
     * @return JsonNode representation of the value, or TextNode if truncated
     */
    public static JsonNode getJsonNodeOrTruncatedString(RowMetadata rowMetadata, String truncatedFlag, Row row,
            String value) {
        if (rowMetadata.contains(truncatedFlag) && Boolean.TRUE.equals(row.get(truncatedFlag, Boolean.class))) {
            return TextNode.valueOf(value);
        }

        try {
            return JsonUtils.getJsonNodeFromString(value);
        } catch (UncheckedIOException e) {
            log.warn("Failed to parse JSON, returning as plain text node. Error: {}", e.getMessage());
            return TextNode.valueOf(value);
        }
    }

    /**
     * Binds the truncation threshold parameter to a statement based on configuration.
     *
     * @param statement the database statement to bind the parameter to
     * @param truncationThresholdField the field name for the truncation threshold parameter
     * @param configuration the Opik configuration containing truncation settings
     */
    public static void bindTruncationThreshold(Statement statement, String truncationThresholdField,
            OpikConfiguration configuration) {
        if (configuration.getResponseFormatting().getTruncationSize() > 0) {
            statement.bind(truncationThresholdField, configuration.getResponseFormatting().getTruncationSize());
        } else {
            statement.bindNull(truncationThresholdField, Integer.class);
        }
    }
}
