package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.beans.ConstructorProperties;

import static com.comet.opik.api.DatasetItemInputValue.InputValueType;
import static com.comet.opik.api.DatasetItemInputValue.JsonValue;
import static com.comet.opik.api.DatasetItemInputValue.StringValue;

@Data
@SuperBuilder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = JsonValue.class, name = InputValueType.JSON_TYPE),
        @JsonSubTypes.Type(value = StringValue.class, name = InputValueType.STRING_TYPE),
})
@Schema(name = "data", discriminatorProperty = "type", discriminatorMapping = {
        @DiscriminatorMapping(value = InputValueType.JSON_TYPE, schema = JsonValue.class),
        @DiscriminatorMapping(value = InputValueType.STRING_TYPE, schema = StringValue.class)
})
@RequiredArgsConstructor
public abstract sealed class DatasetItemInputValue<T> {

    @Getter
    @RequiredArgsConstructor
    public enum InputValueType {

        JSON(InputValueType.JSON_TYPE),
        STRING(InputValueType.STRING_TYPE);

        public static final String JSON_TYPE = "json";
        public static final String STRING_TYPE = "string";

        public static InputValueType fromString(String value) {
            return InputValueType.valueOf(value);
        }

        @com.fasterxml.jackson.annotation.JsonValue
        private final String value;
    }

    @Getter
    @SuperBuilder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static final class JsonValue extends DatasetItemInputValue<JsonNode> {

        @ConstructorProperties({"value"})
        public JsonValue(@NotNull JsonNode value) {
            super(value);
        }

        @Override
        public InputValueType getType() {
            return InputValueType.JSON;
        }
    }

    @Getter
    @SuperBuilder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static final class StringValue extends DatasetItemInputValue<String> {

        @ConstructorProperties({"value"})
        public StringValue(@NotBlank String value) {
            super(value);
        }

        @Override
        public InputValueType getType() {
            return InputValueType.STRING;
        }
    }

    protected final T value;

    public abstract InputValueType getType();
}
