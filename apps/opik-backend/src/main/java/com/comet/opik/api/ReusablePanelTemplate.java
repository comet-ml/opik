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
public record ReusablePanelTemplate(
        @JsonView( {
                View.Public.class}) UUID id,
        @JsonView({View.Public.class, View.Write.class}) @NotBlank String name,
        @JsonView({View.Public.class, View.Write.class}) String description,
        @JsonView({View.Public.class, View.Write.class}) @NotNull DashboardPanel.PanelType type,
        @JsonView({View.Public.class, View.Write.class}) JsonNode configuration,
        @JsonView({View.Public.class, View.Write.class}) JsonNode defaultLayout,
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