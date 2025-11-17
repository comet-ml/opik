package com.comet.opik.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.json.Json;

import java.util.Arrays;
import java.util.UUID;

public sealed interface FeedbackDefinitionModel<T>
        permits NumericalFeedbackDefinitionDefinitionModel, CategoricalFeedbackDefinitionDefinitionModel,
        BooleanFeedbackDefinitionDefinitionModel {

    UUID id();

    String name();

    String description();

    @Json
    T details();

    FeedbackType type();

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    enum FeedbackType {
        NUMERICAL("numerical"),
        CATEGORICAL("categorical"),
        BOOLEAN("boolean");

        @JsonValue
        private final String type;

        public static FeedbackType fromString(String type) {
            return Arrays.stream(values()).filter(v -> v.type.equals(type)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown feedback type: " + type));
        }
    }
}
