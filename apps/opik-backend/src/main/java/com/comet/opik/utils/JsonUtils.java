package com.comet.opik.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.annotations.VisibleForTesting;
import dev.langchain4j.model.openai.internal.chat.Message;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@UtilityClass
@Slf4j
public class JsonUtils {

    /**
     * ObjectMapper for internal JSON processing.
     * Initialized with minimal defaults (20MB) and reconfigured by OpikApplication
     * during startup to match config.yml settings.
     */
    private static volatile ObjectMapper MAPPER;

    static {
        // Initialize with defaults (20MB string, unlimited document) until OpikApplication configures it
        MAPPER = createConfiguredMapper(StreamReadConstraints.DEFAULT_MAX_STRING_LEN, -1L);
        log.info("JsonUtils initialized with default maxStringLength: '{}', maxDocumentLength: unlimited",
                StreamReadConstraints.DEFAULT_MAX_STRING_LEN);
    }

    /**
     * Configures JsonUtils with the limits from config.yml.
     * Called by OpikApplication during startup.
     *
     * @param maxStringLength   Maximum single string value length in bytes
     * @param maxDocumentLength Maximum whole-document length in bytes ({@code <= 0} means unlimited)
     */
    public static synchronized void configure(int maxStringLength, long maxDocumentLength) {
        MAPPER = createConfiguredMapper(maxStringLength, maxDocumentLength);
        log.info("JsonUtils configured with maxStringLength: '{}' bytes ('{}'MB), maxDocumentLength: '{}' bytes",
                maxStringLength, maxStringLength / 1024 / 1024, maxDocumentLength);
    }

    /**
     * Creates and configures an ObjectMapper with the specified limits.
     * This configuration matches the Dropwizard ObjectMapper setup in OpikApplication.
     *
     * @param maxStringLength   Maximum single string value length in bytes
     * @param maxDocumentLength Maximum whole-document length in bytes ({@code <= 0} means unlimited)
     * @return Configured ObjectMapper instance
     */
    @VisibleForTesting
    static ObjectMapper createConfiguredMapper(int maxStringLength, long maxDocumentLength) {
        ObjectMapper mapper = new ObjectMapper();

        // Basic configuration matching Dropwizard defaults
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, false);
        mapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, false);
        mapper.enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature());

        // Register JavaTimeModule for proper date/time handling
        mapper.registerModule(new JavaTimeModule()
                .addDeserializer(BigDecimal.class, JsonBigDecimalDeserializer.INSTANCE)
                .addDeserializer(Message.class, OpenAiMessageJsonDeserializer.INSTANCE)
                .addDeserializer(Duration.class, StrictDurationDeserializer.INSTANCE));

        // Bound a single string value AND the whole document (shared with the Dropwizard HTTP mapper).
        applyStreamReadConstraints(mapper, maxStringLength, maxDocumentLength);

        return mapper;
    }

    /**
     * Applies the JSON stream-read size limits (single-string and whole-document) to {@code mapper}'s
     * factory. Shared by the internal ObjectMapper above and the Dropwizard HTTP ObjectMapper in
     * {@code OpikApplication}, so both carry the same StreamReadConstraints from one place.
     * <p>
     * NOTE on {@code maxDocumentLength}: Jackson enforces it only on a parser buffer refill, which
     * happens for stream/reader inputs (e.g. the Dropwizard HTTP request body) - NOT for a fully
     * in-memory {@code String}/{@code byte[]} that fits a single buffer. So the document cap protects
     * the HTTP ingestion boundary but does not re-check this mapper's in-memory reads; those inputs
     * are already bounded at ingestion. ({@code maxStringLength} applies to both.)
     *
     * @param mapper            the ObjectMapper to configure
     * @param maxStringLength   Maximum single string value length in bytes
     * @param maxDocumentLength Maximum whole-document length in bytes ({@code <= 0} is normalized to
     *                          "unlimited" by the builder, matching the static-init default)
     */
    public static void applyStreamReadConstraints(@NonNull ObjectMapper mapper, int maxStringLength,
            long maxDocumentLength) {
        StreamReadConstraints readConstraints = StreamReadConstraints.builder()
                .maxStringLength(maxStringLength)
                .maxDocumentLength(maxDocumentLength)
                .build();
        mapper.getFactory().setStreamReadConstraints(readConstraints);
    }

    /**
     * Gets the shared ObjectMapper instance.
     *
     * @return The configured ObjectMapper
     */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    /**
     * Creates a new empty ObjectNode.
     *
     * @return A new ObjectNode instance
     */
    public static ObjectNode createObjectNode() {
        return MAPPER.createObjectNode();
    }

    /**
     * Creates a new empty ArrayNode.
     *
     * @return A new ArrayNode instance
     */
    public static ArrayNode createArrayNode() {
        return MAPPER.createArrayNode();
    }

    /**
     * Converts a Java object to a JsonNode.
     *
     * @param value The Java object to convert
     * @return The JsonNode representation
     */
    public static JsonNode valueToTree(@NonNull Object value) {
        return MAPPER.valueToTree(value);
    }

    /**
     * Converts a JsonNode to a Java object of the specified type.
     *
     * @param node The JsonNode to convert
     * @param valueType The target class type
     * @return The converted Java object
     */
    public static <T> T treeToValue(@NonNull JsonNode node, @NonNull Class<T> valueType) {
        try {
            return MAPPER.treeToValue(node, valueType);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Serializes a value to a byte array.
     *
     * @param value The value to serialize
     * @return The serialized byte array
     */
    public static byte[] writeValueAsBytes(@NonNull Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode getJsonNodeFromString(@NonNull String value) {
        try {
            return MAPPER.readTree(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode getJsonNodeFromStringWithFallback(@NonNull String value) {
        try {
            return getJsonNodeFromString(value);
        } catch (UncheckedIOException e) {
            return TextNode.valueOf(value);
        }
    }

    public static JsonNode getJsonNodeFromString(@NonNull InputStream value) {
        try {
            return MAPPER.readTree(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String getStringOrDefault(JsonNode jsonNode) {
        return Optional.ofNullable(jsonNode).map(JsonNode::toString).orElse("");
    }

    public static JsonNode getJsonNodeOrDefault(String str) {
        return Optional.ofNullable(str)
                .filter(s -> !s.isBlank())
                .map(JsonUtils::getJsonNodeFromString)
                .orElse(null);
    }

    public JsonNode readTree(@NonNull Object content) {
        return MAPPER.convertValue(content, JsonNode.class);
    }

    public <T> T readValue(@NonNull String content, @NonNull TypeReference<T> valueTypeRef) {
        try {
            return MAPPER.readValue(content, valueTypeRef);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public <T> T readValue(@NonNull String content, @NonNull Class<T> valueTypeRef) {
        try {
            return MAPPER.readValue(content, valueTypeRef);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public <T> T readValue(@NonNull InputStream inputStream, @NonNull TypeReference<T> valueTypeRef) {
        try {
            return MAPPER.readValue(inputStream, valueTypeRef);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public <T> T readValue(@NonNull byte[] content, @NonNull Class<T> valueTypeRef) {
        try {
            return MAPPER.readValue(content, valueTypeRef);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public <T> T readCollectionValue(@NonNull String content, @NonNull Class<? extends Collection> collectionClass,
            @NonNull Class<?> valueClass) {
        return readCollectionValue(content,
                MAPPER.getTypeFactory().constructCollectionType(collectionClass, valueClass));
    }

    public <T> T readCollectionValue(@NonNull String content, @NonNull CollectionType collectionType) {
        try {
            return MAPPER.readValue(content, collectionType);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public <T> String writeListOrDefaultEmpty(List<T> list) {
        return writeValueAsString(list == null ? List.of() : list);
    }

    public String writeValueAsString(@NonNull Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public void writeValueAsString(@NonNull ByteArrayOutputStream baos, @NonNull Object value) {
        try {
            MAPPER.writeValue(baos, value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public <T> T readJsonFile(@NonNull String fileName, @NonNull TypeReference<T> valueTypeRef) throws IOException {
        try (InputStream inputStream = JsonUtils.class.getClassLoader().getResourceAsStream(fileName)) {
            return MAPPER.readValue(inputStream, valueTypeRef);
        }
    }

    public <T> T convertValue(@NonNull Object fromValue, @NonNull TypeReference<T> toValueTypeRef) {
        return MAPPER.convertValue(fromValue, toValueTypeRef);
    }

    public static JsonNode prependField(
            JsonNode jsonNode,
            @NonNull String fieldName,
            String fieldValue) {
        if (StringUtils.isBlank(fieldValue)) {
            return jsonNode;
        }

        TextNode valueNode = MAPPER.getNodeFactory().textNode(fieldValue);
        return prependField(jsonNode, fieldName, valueNode);
    }

    public static JsonNode prependField(
            JsonNode jsonNode,
            @NonNull String fieldName,
            List<String> fieldValues) {
        if (CollectionUtils.isEmpty(fieldValues)) {
            return jsonNode;
        }

        ArrayNode arrayNode = MAPPER.createArrayNode();
        fieldValues.forEach(arrayNode::add);

        return prependField(jsonNode, fieldName, arrayNode);
    }

    private static JsonNode prependField(
            JsonNode jsonNode,
            @NonNull String fieldKey,
            @NonNull JsonNode fieldValue) {
        ObjectNode result = MAPPER.createObjectNode();
        result.set(fieldKey, fieldValue);

        return copyJsonNode(jsonNode, result);
    }

    private static ObjectNode copyJsonNode(JsonNode jsonNode, @NonNull ObjectNode result) {
        if (jsonNode != null && jsonNode.isObject()) {
            result.setAll((ObjectNode) jsonNode);
        }

        return result;
    }
}
