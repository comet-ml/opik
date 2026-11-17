package com.comet.opik.utils;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.io.SegmentedStringWriter;
import com.fasterxml.jackson.core.util.BufferRecycler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Optional;

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
            return JsonUtils.getMapper().getNodeFactory().nullNode();
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
            log.debug("Input is not valid JSON, falling back to simple truncation: {}", e);
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
     * <p>The result is identical to {@code toJsonString(node)} and {@code createSlimJsonString(toJsonString(node))},
     * without reparsing the serialized JSON:
     * <ul>
     *   <li>When no string value exceeds the max length, the slim copy is the full JSON itself, the same
     *   {@code String} instance, so nothing is serialized twice.</li>
     *   <li>When one does, the slim JSON is written by a single walk of the node, without building a slim tree.</li>
     *   <li>When the node holds a value whose reparse would change it ({@code float}, {@code BigDecimal},
     *   binary or POJO nodes, which a request body never produces), the slim JSON comes from the reparse, as
     *   before.</li>
     * </ul>
     *
     * @param node the payload node (may be null)
     * @return the full and slim JSON strings, both empty if the node is null
     */
    public static JsonWithSlim toJsonWithSlim(JsonNode node) {
        if (node == null) {
            return JsonWithSlim.EMPTY;
        }

        String json = node.toString();
        if (json.isEmpty()) {
            return JsonWithSlim.builder().json(json).slim(json).build();
        }

        String slim = switch (inspect(node, DEFAULT_SLIM_STRING_MAX_LENGTH, Integer.MAX_VALUE)) {
            case UNCHANGED -> json;
            case TRUNCATED -> writeSlimJsonString(node, DEFAULT_SLIM_STRING_MAX_LENGTH);
            // TOO_DEEP is unreachable without a depth bound; the reparse is correct for any node.
            case REPARSE, TOO_DEEP -> createSlimJsonString(json);
        };

        return JsonWithSlim.builder().json(json).slim(slim).build();
    }

    /**
     * The full and slim JSON strings of a payload node.
     *
     * @param json the full JSON, as {@link #toJsonString(JsonNode)} renders it
     * @param slim the slim JSON, as {@link #createSlimJsonString(String)} renders it from {@code json}
     */
    @Builder
    public record JsonWithSlim(@NonNull String json, @NonNull String slim) {

        public static final JsonWithSlim EMPTY = JsonWithSlim.builder().json("").slim("").build();
    }

    /**
     * The slim JSON of a payload node as a value that writes itself into the caller's generator, for a row that
     * embeds the payload as raw JSON rather than as a string. Writes the same text
     * {@link #toJsonWithSlim(JsonNode)} returns as {@code slim}, without materializing it as a {@code String}
     * except on the reparse path.
     *
     * <p>Empty when the node nests more than {@code maxDepth} containers deep, so the caller can keep such a payload
     * on the string path: embedded in an enclosing document, it would exceed the generator's nesting limit that
     * {@code node.toString()} alone stays within. Checked in the same walk that inspects the strings.
     *
     * @param node     the payload node
     * @param maxDepth the deepest container nesting to accept, the root container counting as one
     * @return the slim JSON, written when the enclosing value is serialized, or empty if the node is too deep
     */
    public static Optional<JsonSerializable> toSlimJson(@NonNull JsonNode node, int maxDepth) {
        SlimShape shape = inspect(node, DEFAULT_SLIM_STRING_MAX_LENGTH, maxDepth);
        return switch (shape) {
            case TOO_DEEP -> Optional.empty();
            case UNCHANGED, TRUNCATED, REPARSE -> Optional.of(new SlimJson(node, shape));
        };
    }

    private record SlimJson(JsonNode node, SlimShape shape) implements JsonSerializable {

        @Override
        public void serialize(JsonGenerator generator, SerializerProvider provider) throws IOException {
            switch (shape) {
                case UNCHANGED -> node.serialize(generator, provider);
                case TRUNCATED -> writeSlimJson(generator, node, DEFAULT_SLIM_STRING_MAX_LENGTH, provider);
                // TOO_DEEP is never constructed; the reparse is correct for any node.
                case REPARSE, TOO_DEEP -> generator.writeRawValue(createSlimJsonString(node.toString()));
            }
        }

        @Override
        public void serializeWithType(JsonGenerator generator, SerializerProvider provider,
                TypeSerializer typeSerializer) throws IOException {
            serialize(generator, provider);
        }
    }

    private enum SlimShape {
        UNCHANGED,
        TRUNCATED,
        REPARSE,
        TOO_DEEP
    }

    /**
     * Walks the node without allocating copies. Stops at the first container deeper than {@code depthLeft} allows;
     * otherwise reports whether any value needs the reparse path, or else whether any string value exceeds the
     * max length.
     */
    private static SlimShape inspect(JsonNode node, int maxStringLength, int depthLeft) {
        return switch (node.getNodeType()) {
            case OBJECT, ARRAY -> depthLeft <= 0
                    ? SlimShape.TOO_DEEP
                    : inspectChildren(node.elements(), maxStringLength, depthLeft - 1);
            case STRING -> node.textValue().length() > maxStringLength ? SlimShape.TRUNCATED : SlimShape.UNCHANGED;
            case NUMBER -> node.isFloat() || node.isBigDecimal() ? SlimShape.REPARSE : SlimShape.UNCHANGED;
            case BOOLEAN, NULL -> SlimShape.UNCHANGED;
            case BINARY, MISSING, POJO -> SlimShape.REPARSE;
        };
    }

    private static SlimShape inspectChildren(Iterator<JsonNode> children, int maxStringLength, int depthLeft) {
        SlimShape shape = SlimShape.UNCHANGED;
        while (children.hasNext()) {
            switch (inspect(children.next(), maxStringLength, depthLeft)) {
                case TOO_DEEP -> {
                    return SlimShape.TOO_DEEP;
                }
                case REPARSE -> shape = SlimShape.REPARSE;
                case TRUNCATED -> shape = shape == SlimShape.UNCHANGED ? SlimShape.TRUNCATED : shape;
                case UNCHANGED -> {
                }
            }
        }
        return shape;
    }

    /**
     * Writes the slim JSON of a node in one pass, with the generator and settings
     * {@link JsonUtils#writeValueAsString(Object)} uses, so the output matches serializing
     * {@link #createSlimJson(JsonNode, int)}.
     */
    private static String writeSlimJsonString(JsonNode node, int maxStringLength) {
        ObjectMapper mapper = JsonUtils.getMapper();
        BufferRecycler recycler = mapper.getFactory()._getBufferRecycler();
        try (var writer = new SegmentedStringWriter(recycler)) {
            try (JsonGenerator generator = mapper.createGenerator(writer)) {
                writeSlimJson(generator, node, maxStringLength, mapper.getSerializerProviderInstance());
            }
            return writer.getAndClear();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            recycler.releaseToPool();
        }
    }

    private static void writeSlimJson(JsonGenerator generator, JsonNode node, int maxStringLength,
            SerializerProvider provider) throws IOException {
        switch (node.getNodeType()) {
            case OBJECT -> {
                generator.writeStartObject(node, node.size());
                for (var property : node.properties()) {
                    generator.writeFieldName(property.getKey());
                    writeSlimJson(generator, property.getValue(), maxStringLength, provider);
                }
                generator.writeEndObject();
            }
            case ARRAY -> {
                generator.writeStartArray(node, node.size());
                for (JsonNode element : node) {
                    writeSlimJson(generator, element, maxStringLength, provider);
                }
                generator.writeEndArray();
            }
            case STRING -> generator.writeString(truncateString(node.textValue(), maxStringLength));
            case BINARY, BOOLEAN, MISSING, NULL, NUMBER, POJO -> node.serialize(generator, provider);
        }
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
