package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
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
public record Endpoint(
        @JsonView( {
                View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({View.Public.class, View.Write.class}) @NotNull UUID projectId,
        @JsonView({View.Public.class, View.Write.class}) @NotBlank @Size(max = 255) String name,
        @JsonView({View.Public.class, View.Write.class}) @NotBlank @Size(max = 2048) String url,
        @JsonView({View.Write.class}) @NotBlank String secret,
        @JsonView({View.Public.class, View.Write.class}) @Nullable String schemaJson,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EndpointPage(
            @JsonView( {
                    View.Public.class}) int page,
            @JsonView({View.Public.class}) int size,
            @JsonView({View.Public.class}) long total,
            @JsonView({View.Public.class}) List<Endpoint> content,
            @JsonView({View.Public.class}) List<String> sortableBy)
            implements
                Page<Endpoint>{

        public static EndpointPage empty(int page) {
            return new EndpointPage(page, 0, 0, List.of(), List.of());
        }
    }
}
