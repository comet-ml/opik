package com.comet.opik.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OptimizerConfigValue(
        UUID id,
        UUID projectId,
        @NotBlank @Size(max = 255, message = "key cannot exceed 255 characters") String key,
        @NotBlank @Size(max = 255, message = "value cannot exceed 255 characters") String value,
        @NotNull ValueType type,
        UUID validFromBlueprintId,
        UUID validToBlueprintId) {

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public enum ValueType {
        STRING("string"),
        NUMBER("number"),
        PROMPT("prompt"),
        PROMPTVERSION("promptversion");

        @JsonValue
        private final String type;

        public static ValueType fromString(String type) {
            return Arrays.stream(values())
                    .filter(v -> v.type.equals(type))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown value type: " + type));
        }
    }
}
