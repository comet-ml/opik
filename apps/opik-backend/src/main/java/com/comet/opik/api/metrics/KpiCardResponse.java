package com.comet.opik.api.metrics;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KpiCardResponse(List<KpiMetric> stats) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record KpiMetric(
            KpiMetricType type,
            @JsonInclude(JsonInclude.Include.ALWAYS) Double currentValue,
            @JsonInclude(JsonInclude.Include.ALWAYS) Double previousValue) {
    }

    @Getter
    @RequiredArgsConstructor
    public enum KpiMetricType {
        COUNT("count"),
        ERRORS("errors"),
        AVG_DURATION("avg_duration"),
        TOTAL_COST("total_cost");

        @JsonValue
        private final String value;

        @JsonCreator
        public static KpiMetricType fromString(String value) {
            return Arrays.stream(values())
                    .filter(type -> type.value.equalsIgnoreCase(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown KPI metric type '%s'. Valid values are: count, errors, avg_duration, total_cost"
                                    .formatted(value)));
        }
    }
}
