package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.io.UncheckedIOException;

/**
 * The opik-inference wire mapper. It is separate from the API mappers so the wire format can't drift with them, and it
 * sorts object keys so the same value always serializes to the same bytes (the canonical JSON).
 */
@UtilityClass
public class InferenceJson {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(JsonNodeFeature.WRITE_PROPERTIES_SORTED, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    /**
     * Fails a {@code null} or missing value for a primitive instead of reading it as 0: a vector with a null in it
     * then fails, instead of reading as a valid one. Not the mapper's default, because it also fails an optional
     * primitive the service leaves out, such as {@code usage.input_tokens}.
     */
    public static final ObjectReader NO_NULL_PRIMITIVES = MAPPER.reader()
            .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public static byte[] writeBytes(@NonNull Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public static String canonical(@NonNull JsonNode value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
