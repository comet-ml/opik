package com.comet.opik.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.util.Collection;
import java.util.Optional;

@UtilityClass
@Slf4j
public class JsonUtils {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.INDENT_OUTPUT, false)
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature())
            .registerModule(new JavaTimeModule()
                    .addDeserializer(BigDecimal.class, JsonBigDecimalDeserializer.INSTANCE)
                    .addDeserializer(Message.class, OpenAiMessageJsonDeserializer.INSTANCE));

    public static JsonNode getJsonNodeFromString(@NonNull String value) {
        try {
            return MAPPER.readTree(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
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
}
