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
public record Dashboard(
        @JsonView( {
                Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({
                Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @ColumnName("workspace_id") String workspaceId,
        @JsonView({Dashboard.View.Public.class,
                Dashboard.View.Write.class}) @NotBlank @Size(max = 255) String name,
        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) @Nullable String description,
        @JsonView({Dashboard.View.Public.class,
                Dashboard.View.Write.class}) @NotNull @Schema(defaultValue = "custom") @ColumnName("dashboard_type") DashboardType type,
        @JsonView({
                Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @ColumnName("is_default") boolean isDefault,
        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) @Nullable List<UUID> projectIds,
        @JsonView({Dashboard.View.Public.class}) @Valid @Nullable List<DashboardChart> charts,
        @JsonView({
                Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @ColumnName("created_at") @Nullable Instant createdAt,
        @JsonView({
                Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @ColumnName("created_by") @Nullable String createdBy,
        @JsonView({
                Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @ColumnName("last_updated_at") @Nullable Instant lastUpdatedAt,
        @JsonView({
                Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @ColumnName("last_updated_by") @Nullable String lastUpdatedBy){

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DashboardPage(
            @JsonView( {
                    Dashboard.View.Public.class}) int page,
            @JsonView({Dashboard.View.Public.class}) int size,
            @JsonView({Dashboard.View.Public.class}) long total,
            @JsonView({Dashboard.View.Public.class}) List<Dashboard> content,
            @JsonView({Dashboard.View.Public.class}) List<String> sortableBy)
            implements
                Page<Dashboard>{

        public static DashboardPage empty(int page, List<String> sortableBy) {
            return new DashboardPage(page, 0, 0, List.of(), sortableBy);
        }
    }
}
