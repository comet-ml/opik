package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Environment(
        @JsonView( {
                Environment.View.Public.class, Environment.View.Write.class}) UUID id,
        @JsonView({Environment.View.Public.class,
                Environment.View.Write.class}) @NotBlank @Pattern(regexp = Environment.NAME_PATTERN, message = Environment.NAME_PATTERN_MESSAGE) @Size(max = 150, message = "cannot exceed 150 characters") String name,
        @JsonView({Environment.View.Public.class,
                Environment.View.Write.class}) @Size(max = 500, message = "cannot exceed 500 characters") String description,
        @JsonView({Environment.View.Public.class,
                Environment.View.Write.class}) @Size(max = 20, message = "cannot exceed 20 characters") String color,
        @JsonView({Environment.View.Public.class, Environment.View.Write.class}) Integer position,
        @JsonView({Environment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({Environment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({
                Environment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({
                Environment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

    public static final String NAME_PATTERN = "^[A-Za-z0-9_-]+$";
    public static final String NAME_PATTERN_MESSAGE = "must match '" + NAME_PATTERN + "'";

    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }

    public record EnvironmentPage(
            @JsonView( {
                    Environment.View.Public.class}) int page,
            @JsonView({Environment.View.Public.class}) int size,
            @JsonView({Environment.View.Public.class}) long total,
            @JsonView({Environment.View.Public.class}) List<Environment> content,
            @JsonView({Environment.View.Public.class}) List<String> sortableBy)
            implements
                com.comet.opik.api.Page<Environment>{

        public static EnvironmentPage empty() {
            return new EnvironmentPage(1, 0, 0, List.of(), List.of("created_at"));
        }
    }
}
