package com.comet.opik.api.metrics;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Arrays;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KpiCardRequest(
        @NotNull EntityType entityType,
        String filters,
        @NotNull Instant intervalStart,
        Instant intervalEnd) {

    @JsonIgnore
    @AssertTrue(message = "intervalStart must be before intervalEnd or current time") public boolean isStartBeforeEnd() {
        if (intervalStart == null) {
            return true;
        }
        if (intervalEnd != null) {
            return intervalStart.isBefore(intervalEnd);
        }
        return intervalStart.isBefore(Instant.now());
    }

    @Getter
    @RequiredArgsConstructor
    public enum EntityType {
        TRACES("traces"),
        SPANS("spans"),
        THREADS("threads");

        @JsonValue
        private final String value;

        @JsonCreator
        public static EntityType fromString(String value) {
            return Arrays.stream(values())
                    .filter(type -> type.value.equalsIgnoreCase(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown entity type '%s'. Valid values are: traces, spans, threads".formatted(value)));
        }
    }
}
