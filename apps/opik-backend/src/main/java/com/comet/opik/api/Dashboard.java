package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Dashboard(
        @JsonView( {
                Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String workspaceId,
        @JsonView({Dashboard.View.Public.class,
                Dashboard.View.Write.class}) @NotBlank @Size(min = 1, max = 120, message = "name must be between 1 and 120 characters") String name,
        @JsonView({Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String slug,
        @JsonView({Dashboard.View.Public.class,
                Dashboard.View.Write.class}) @Nullable @Size(max = 1000, message = "description cannot exceed 1000 characters") String description,
        @JsonView({Dashboard.View.Public.class,
                Dashboard.View.Write.class}) @NotNull JsonNode config,
        @JsonView({
                Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable String createdBy,
        @JsonView({
                Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable String lastUpdatedBy,
        @JsonView({Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({
                Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt){

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
                    Dashboard.View.Public.class}) List<Dashboard> content,
            @JsonView({Dashboard.View.Public.class}) int page,
            @JsonView({Dashboard.View.Public.class}) int size,
            @JsonView({Dashboard.View.Public.class}) long total,
            @JsonView({Dashboard.View.Public.class}) List<String> sortableBy) implements Page<Dashboard>{

        public static DashboardPage empty(int page, List<String> sortableBy) {
            return new DashboardPage(List.of(), page, 0, 0, sortableBy);
        }
    }
}
