package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
public record DashboardChart(
        @JsonView( {
                Dashboard.View.Public.class,
                DashboardChart.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({Dashboard.View.Public.class,
                DashboardChart.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @ColumnName("dashboard_id") UUID dashboardId,
        @JsonView({Dashboard.View.Public.class, DashboardChart.View.Public.class,
                DashboardChart.View.Write.class}) @NotBlank @Size(max = 255) String name,
        @JsonView({Dashboard.View.Public.class, DashboardChart.View.Public.class,
                DashboardChart.View.Write.class}) @Nullable String description,
        @JsonView({Dashboard.View.Public.class, DashboardChart.View.Public.class,
                DashboardChart.View.Write.class}) @NotNull @Schema(defaultValue = "line") @ColumnName("chart_type") ChartType chartType,
        @JsonView({Dashboard.View.Public.class, DashboardChart.View.Public.class,
                DashboardChart.View.Write.class}) @Valid @Nullable ChartPosition position,
        @JsonView({Dashboard.View.Public.class, DashboardChart.View.Public.class,
                DashboardChart.View.Write.class}) @Valid @Nullable @ColumnName("data_series") List<DataSeries> dataSeries,
        @JsonView({Dashboard.View.Public.class, DashboardChart.View.Public.class,
                DashboardChart.View.Write.class}) @Valid @Nullable @ColumnName("group_by") GroupByConfig groupBy,
        @JsonView({Dashboard.View.Public.class,
                DashboardChart.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @ColumnName("created_at") @Nullable Instant createdAt,
        @JsonView({Dashboard.View.Public.class,
                DashboardChart.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @ColumnName("created_by") @Nullable String createdBy,
        @JsonView({Dashboard.View.Public.class,
                DashboardChart.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @ColumnName("last_updated_at") @Nullable Instant lastUpdatedAt,
        @JsonView({Dashboard.View.Public.class,
                DashboardChart.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @ColumnName("last_updated_by") @Nullable String lastUpdatedBy){

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }
}
