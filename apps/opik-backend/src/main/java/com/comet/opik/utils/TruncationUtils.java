package com.comet.opik.utils;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.node.TextNode;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

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
    static final String TRUNCATION_SUFFIX = "...";

    /**
     * Converts a JsonNode to its string representation, returning empty string for null.
     * This provides consistent null handling across all DAO classes.
     *
     * @param node the JSON node to convert (may be null)
     * @return the JSON string representation, or empty string if null
     */
    public static String toJsonString(JsonNode node) {
        return node != null ? node.toString() : "";
    }

    /**
     * Serializes a payload node into the strings stored in its full column ({@code input}, {@code output}) and
     * its slim column ({@code input_slim}, {@code output_slim}), with the default max length.
     *
     * <p>When no string value exceeds the max length, the slim copy is the full JSON itself, the same
     * {@code String} instance. Otherwise it is {@link #slimJsonSerializable(JsonNode)}, written in one walk of the
     * node.
     *
     * @param node the payload node (may be null)
     * @return the full and slim JSON strings, both empty if the node is null
     */
    public static JsonWithSlim toJsonWithSlim(JsonNode node) {
        if (node == null) {
            return JsonWithSlim.EMPTY;
        }

        String json = node.toString();
        if (json.isEmpty() || !hasLongString(node, DEFAULT_SLIM_STRING_MAX_LENGTH)) {
            return JsonWithSlim.builder().json(json).slim(json).build();
        }

        try {
            return JsonWithSlim.builder().json(json)
                    .slim(PayloadJson.NODE_WRITER.writeValueAsString(slimJsonSerializable(node)))
                    .build();
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * The full JSON of a payload node, as a value that writes itself into the caller's generator: the same text as
     * {@link #toJsonString(JsonNode)}, without materializing it.
     *
     * <p>Written through a default mapper's writer, as {@code node.toString()} is, not the caller's, so the text does
     * not depend on the configuration of the mapper that writes the enclosing value.
     *
     * @param node the payload node
     * @return the full JSON, written when the enclosing value is serialized
     */
    public static JsonSerializable fullJsonSerializable(@NonNull JsonNode node) {
        return PayloadJson.builder().node(node).slim(false).build();
    }

    /**
     * The slim JSON of a payload node, as a value that writes itself into the caller's generator: every string
     * value longer than the default max length is cut to it and followed by {@code "..."}, keys and structure are
     * kept. Like {@link #fullJsonSerializable(JsonNode)}, it does not depend on the caller's mapper configuration.
     *
     * @param node the payload node
     * @return the slim JSON, written when the enclosing value is serialized
     */
    public static JsonSerializable slimJsonSerializable(@NonNull JsonNode node) {
        return PayloadJson.builder().node(node).slim(true).build();
    }

    /**
     * Whether any string value is longer than the max length; stops at the first one. Walks with a stack of child
     * iterators rather than recursion, so depth costs heap rather than call stack, in the same order and with the same
     * early exit.
     */
    private static boolean hasLongString(JsonNode node, int maxStringLength) {
        if (!node.isContainerNode()) {
            return node.isTextual() && node.textValue().length() > maxStringLength;
        }
        Deque<Iterator<JsonNode>> pending = new ArrayDeque<>();
        pending.push(node.iterator());
        while (!pending.isEmpty()) {
            Iterator<JsonNode> children = pending.peek();
            if (!children.hasNext()) {
                pending.pop();
                continue;
            }
            JsonNode child = children.next();
            switch (child.getNodeType()) {
                case OBJECT, ARRAY -> pending.push(child.iterator());
                case STRING -> {
                    if (child.textValue().length() > maxStringLength) {
                        return true;
                    }
                }
                case BINARY, BOOLEAN, MISSING, NULL, NUMBER, POJO -> {
                }
            }
        }
        return false;
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
