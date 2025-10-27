package com.comet.opik.api;

import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.metrics.MetricType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import org.jdbi.v3.core.mapper.reflect.ColumnName;

import javax.annotation.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DataSeries(
        @JsonView(DataSeries.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @ColumnName("project_id") @Nullable UUID projectId,
        @NotNull @ColumnName("metric_type") MetricType metricType,
        @Size(max = 255) @Nullable String name,
        @Valid @Nullable List<Filter> filters,
        @Size(max = 7) @Nullable String color,
        @Min(0) @Schema(defaultValue = "0") @ColumnName("series_order") @Nullable Integer order,
        @JsonView(DataSeries.View.Public.class) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @ColumnName("created_at") @Nullable Instant createdAt) {

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }
}
