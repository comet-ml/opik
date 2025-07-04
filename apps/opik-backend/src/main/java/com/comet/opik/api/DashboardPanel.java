package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DashboardPanel(
        @JsonView( {
                Dashboard.View.Public.class}) UUID id,
        @JsonView({Dashboard.View.Public.class}) UUID sectionId,
        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) @NotBlank String name,
        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) @NotNull PanelType type,
        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) JsonNode configuration,
        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) JsonNode layout,
        @JsonView({Dashboard.View.Public.class}) Instant createdAt,
        @JsonView({Dashboard.View.Public.class}) String createdBy,
        @JsonView({Dashboard.View.Public.class}) Instant lastUpdatedAt,
        @JsonView({Dashboard.View.Public.class}) String lastUpdatedBy){

    public enum PanelType {
        PYTHON,
        CHART,
        TEXT,
        METRIC,
        HTML
    }
}
