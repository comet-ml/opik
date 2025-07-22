package com.comet.opik.api.dashboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Dashboard(
        @JsonView( {
                Dashboard.View.Public.class, Dashboard.View.Write.class}) UUID id,

        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) @NotBlank String name,

        @JsonView({Dashboard.View.Public.class,
                Dashboard.View.Write.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String description,

        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) @NotNull @Valid DashboardLayout layout,

        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) @NotNull Map<String, Object> filters,

        @JsonView({Dashboard.View.Public.class, Dashboard.View.Write.class}) @Min(5) Integer refreshInterval,

        @JsonView({Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,

        @JsonView({Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,

        @JsonView({
                Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,

        @JsonView({
                Dashboard.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

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

            @JsonView({Dashboard.View.Public.class}) long total){
    }
}
