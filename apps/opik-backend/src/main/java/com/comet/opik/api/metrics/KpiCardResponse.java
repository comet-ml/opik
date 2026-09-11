package com.comet.opik.api.metrics;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
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
            @JsonInclude(JsonInclude.Include.ALWAYS) @Schema(description = "Metric value for the current period. Unit depends on `type`: `count` is an integer count; `errors` is a percentage in [0, 100]; `avg_duration` is milliseconds; `total_cost` is in USD.") Double currentValue,
            @JsonInclude(JsonInclude.Include.ALWAYS) @Schema(description = "Metric value for the immediately preceding period of equal length. Same unit as `current_value`.") Double previousValue) {
    }

    @Getter
    @RequiredArgsConstructor
    public enum KpiMetricType {
        COUNT("count"),
        /** Errors KPI — value is a percentage in the range [0, 100], with 0 returned when the period has no entities. */
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
