package com.comet.opik.api.metrics;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.TimeInterval;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectMetricResponse<T extends Number>(
        UUID projectId,
        MetricType metricType,
        TimeInterval interval,
        BreakdownField breakdownField,
        Integer totalGroups,
        Integer groupsShown,
        List<Results<T>> results) {

    public static final ProjectMetricResponse<Number> EMPTY = ProjectMetricResponse.builder()
            .results(List.of())
            .build();

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Results<T extends Number>(
            String name,
            String displayName,
            List<DataPoint<T>> data) {

        /**
         * Check if this result represents the "Others" aggregated group.
         */
        public boolean isOthersGroup() {
            return BreakdownConfig.OTHERS_GROUP_NAME.equals(name);
        }
    }
}
