package com.comet.opik.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import dev.langchain4j.model.openai.internal.chat.Message;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

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

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.INDENT_OUTPUT, false)
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, false)
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature())
            .registerModule(new JavaTimeModule()
                    .addDeserializer(BigDecimal.class, JsonBigDecimalDeserializer.INSTANCE)
                    .addDeserializer(Message.class, OpenAiMessageJsonDeserializer.INSTANCE)
                    .addDeserializer(Duration.class, StrictDurationDeserializer.INSTANCE));

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

    /**
     * Injects a field at the beginning of metadata JsonNode.
     * Creates a new ObjectNode with the field first, followed by existing metadata fields.
     *
     * @param metadata existing metadata JsonNode (can be null)
     * @param fieldName name of the field to inject
     * @param fieldValue value to inject (String)
     * @return new JsonNode with field at the beginning, or original metadata if fieldValue is null/blank
     */
    public static JsonNode injectStringFieldIntoMetadata(JsonNode metadata, String fieldName, String fieldValue) {
        if (fieldValue == null || fieldValue.isBlank()) {
            return metadata;
        }

        TextNode valueNode = MAPPER.getNodeFactory().textNode(fieldValue);
        return injectFieldIntoMetadata(metadata, fieldName, valueNode);
    }

    /**
     * Injects a field with array value at the beginning of metadata JsonNode.
     * Creates a new ObjectNode with the field first, followed by existing metadata fields.
     *
     * @param metadata existing metadata JsonNode (can be null)
     * @param fieldName name of the field to inject
     * @param fieldValues list of strings to inject as array
     * @return new JsonNode with field at the beginning, or original metadata if fieldValues is null/empty
     */
    public static JsonNode injectArrayFieldIntoMetadata(JsonNode metadata, String fieldName, List<String> fieldValues) {
        if (fieldValues == null || fieldValues.isEmpty()) {
            return metadata;
        }

        ArrayNode arrayNode = MAPPER.createArrayNode();
        fieldValues.forEach(arrayNode::add);

        return injectFieldIntoMetadata(metadata, fieldName, arrayNode);
    }

    private static JsonNode injectFieldIntoMetadata(
            JsonNode metadata,
            String fieldKey,
            JsonNode fieldValue) {
        // 1. Create result and inject the payload field
        ObjectNode result = MAPPER.createObjectNode();
        result.set(fieldKey, fieldValue);

        // 2. Delegate copying the common metadata fields
        return copyMetadataFields(metadata, result);
    }

    /**
     * Copies existing fields from metadata into the 'result' ObjectNode,
     * maintaining insertion order after any fields already in 'result'.
     *
     * @param metadata The existing metadata to copy fields from (must be an object, or null)
     * @param result The ObjectNode already containing the injected field(s).
     * @return The final ObjectNode with all fields.
     */
    private static ObjectNode copyMetadataFields(JsonNode metadata, ObjectNode result) {
        if (metadata != null && metadata.isObject()) {
            result.setAll((ObjectNode) metadata);
        }

        return result;
    }
}
