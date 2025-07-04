package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DashboardSection(
        @JsonView( {
                Dashboard.View.Public.class}) UUID id,
        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) @NotBlank String title,
        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) Integer positionOrder,
        @JsonView({Dashboard.View.Public.class}) @Valid List<DashboardPanel> panels,
        @JsonView({Dashboard.View.Public.class}) Instant createdAt,
        @JsonView({Dashboard.View.Public.class}) String createdBy,
        @JsonView({Dashboard.View.Public.class}) Instant lastUpdatedAt,
        @JsonView({Dashboard.View.Public.class}) String lastUpdatedBy){
}
