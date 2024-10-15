package com.comet.opik.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

public record DatasetItemData(@NotNull JsonNode value, @NotNull Type type) {

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    @Getter
    public enum Type {
        ARRAY("array"),
        BOOLEAN("boolean"),
        JSON("json"),
        NUMBER("number"),
        STRING("string");

        @JsonValue
        private final String type;
    }

    public static Type getType(JsonNode value) {
        return switch (value.getNodeType()) {
            case ARRAY -> Type.ARRAY;
            case BINARY, MISSING, NULL, STRING -> Type.STRING;
            case BOOLEAN -> Type.BOOLEAN;
            case NUMBER -> Type.NUMBER;
            case OBJECT, POJO -> Type.JSON;
        };
    }
}
