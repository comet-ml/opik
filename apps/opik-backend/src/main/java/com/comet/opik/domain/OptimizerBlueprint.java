package com.comet.opik.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OptimizerBlueprint(
        UUID id,
        UUID projectId,
        @NotNull UUID configId,
        @NotNull BlueprintType type,
        @Size(max = 255, message = "description cannot exceed 255 characters") String description,
        String createdBy,
        Instant createdAt,
        String lastUpdatedBy,
        Instant lastUpdatedAt) {

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public enum BlueprintType {
        BLUEPRINT("blueprint"),
        MASK("mask");

        @JsonValue
        private final String type;

        public static BlueprintType fromString(String type) {
            return Arrays.stream(values())
                    .filter(v -> v.type.equals(type))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown blueprint type: " + type));
        }
    }
}
