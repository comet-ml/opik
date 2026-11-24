package com.comet.opik.utils;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.util.ArrayDeque;
import java.util.Deque;

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
    public static final String TRUNCATION_SUFFIX = "...";

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
     * <p>When no string value exceeds the max length, the slim copy is the full JSON itself, the same
     * {@code String} instance. Otherwise it is {@link #slimJsonSerializable(JsonNode)}, written in one walk of the
     * node. For a node parsed from JSON, both match {@code toJsonString(node)} and
     * {@code createSlimJsonString(toJsonString(node))}, the string path's columns.
     *
     * <p>Only for a node parsed from JSON. In a node built in code, a value can render differently once reparsed, so
     * the slim copy can differ from the string path's while the full JSON matches. A {@code BigDecimal} of 10, as
     * {@code valueToTree} builds online-evaluation scores, renders as {@code 1E+1}: kept here, {@code 10.0} once
     * reparsed. Float, binary and POJO values differ the same way. Reads parse the slim JSON, so they get the same
     * value either way.
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
     * The full and slim JSON strings of a payload node.
     *
     * @param json the full JSON, as {@link #toJsonString(JsonNode)} renders it
     * @param slim the slim JSON, as {@link #slimJsonSerializable(JsonNode)} writes it
     */
    @Builder(toBuilder = true)
    public record JsonWithSlim(@NonNull String json, @NonNull String slim) {

        public static final JsonWithSlim EMPTY = JsonWithSlim.builder().json("").slim("").build();
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

    @Builder(toBuilder = true)
    private record PayloadJson(@NonNull JsonNode node, boolean slim) implements JsonSerializable {

        /**
         * A default mapper's writer, as {@code JsonNode.toString()} uses, without flushing after each value: the payload
         * is written into the enclosing row, which the caller flushes. Each write gets its own serializer provider.
         */
        private static final ObjectWriter NODE_WRITER = new ObjectMapper().writer()
                .without(SerializationFeature.FLUSH_AFTER_WRITE_VALUE);

        @Override
        public void serialize(JsonGenerator generator, SerializerProvider provider) throws IOException {
            if (slim) {
                NODE_WRITER.writeValue(new TruncatingGenerator(generator, DEFAULT_SLIM_STRING_MAX_LENGTH), node);
                return;
            }
            NODE_WRITER.writeValue(generator, node);
        }

        @Override
        public void serializeWithType(JsonGenerator generator, SerializerProvider provider,
                TypeSerializer typeSerializer) throws IOException {
            serialize(generator, provider);
        }
    }

    /**
     * Truncates string values as Jackson writes them, to the same chars {@link #truncateString} returns. Field
     * names are written through {@code writeFieldName}, so they are kept whole.
     */
    private static class TruncatingGenerator extends JsonGeneratorDelegate {

        private final int maxLength;

        /**
         * One buffer per walk for every truncated string, rather than a substring and a concatenation each. Allocated
         * on the first one, as most payloads have none.
         */
        private char[] truncated;

        private TruncatingGenerator(JsonGenerator delegate, int maxLength) {
            super(delegate, false);
            this.maxLength = maxLength;
        }

        private char[] truncated() {
            if (truncated == null) {
                truncated = new char[maxLength + TRUNCATION_SUFFIX.length()];
                TRUNCATION_SUFFIX.getChars(0, TRUNCATION_SUFFIX.length(), truncated, maxLength);
            }
            return truncated;
        }

        @Override
        public void writeString(String text) throws IOException {
            if (text == null || text.length() <= maxLength) {
                delegate.writeString(text);
                return;
            }
            char[] buffer = truncated();
            text.getChars(0, maxLength, buffer, 0);
            delegate.writeString(buffer, 0, buffer.length);
        }

        @Override
        public void writeString(char[] text, int offset, int length) throws IOException {
            if (length <= maxLength) {
                delegate.writeString(text, offset, length);
                return;
            }
            char[] buffer = truncated();
            System.arraycopy(text, offset, buffer, 0, maxLength);
            delegate.writeString(buffer, 0, buffer.length);
        }

        @Override
        public void writeString(SerializableString text) throws IOException {
            writeString(text.getValue());
        }
    }

    /**
     * Whether any string value is longer than the max length; stops at the first one. Walks with an explicit stack,
     * as {@code JsonNode.toString()} does, so depth costs heap rather than call stack.
     */
    private static boolean hasLongString(JsonNode node, int maxStringLength) {
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.push(node);
        while (!pending.isEmpty()) {
            JsonNode current = pending.pop();
            switch (current.getNodeType()) {
                case OBJECT, ARRAY -> current.forEach(pending::push);
                case STRING -> {
                    if (current.textValue().length() > maxStringLength) {
                        return true;
                    }
                }
                case BINARY, BOOLEAN, MISSING, NULL, NUMBER, POJO -> {
                }
            }
        }
        return false;
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
