package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExperimentDashboard(
        @JsonView( {
                View.Public.class, View.Write.class}) @NotNull UUID experimentId,
        @JsonView({View.Public.class, View.Write.class}) @NotNull UUID dashboardId,
        @JsonView({View.Public.class}) String workspaceId,
        @JsonView({View.Public.class}) Instant createdAt,
        @JsonView({View.Public.class}) String createdBy,
        @JsonView({View.Public.class}) Instant lastUpdatedAt,
        @JsonView({View.Public.class}) String lastUpdatedBy){

    public static class View {
        public static class Write {
        }
        public static class Public {
        }
    }
}
