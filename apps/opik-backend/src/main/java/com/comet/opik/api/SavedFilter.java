package com.comet.opik.api;

import com.comet.opik.api.filter.Filter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SavedFilter(
        @JsonView( {
                SavedFilter.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({SavedFilter.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String workspaceId,
        @JsonView({SavedFilter.View.Public.class,
                SavedFilter.View.Write.class}) @NotNull @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) UUID projectId,
        @JsonView({SavedFilter.View.Public.class,
                SavedFilter.View.Write.class}) @NotBlank @Size(max = 255) String name,
        @JsonView({SavedFilter.View.Public.class, SavedFilter.View.Write.class}) String description,
        @JsonView({SavedFilter.View.Public.class, SavedFilter.View.Write.class}) @Valid @NotEmpty List<Filter> filters,
        @JsonView({SavedFilter.View.Public.class,
                SavedFilter.View.Write.class}) @NotNull @Schema(defaultValue = "trace") FilterType filterType,
        @JsonView({SavedFilter.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({SavedFilter.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({
                SavedFilter.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({
                SavedFilter.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){

    public static class View {
        public static class Public {
        }

        public static class Write {
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SavedFilterPage(
            @JsonView( {
                    SavedFilter.View.Public.class}) int page,
            @JsonView({SavedFilter.View.Public.class}) int size,
            @JsonView({SavedFilter.View.Public.class}) long total,
            @JsonView({SavedFilter.View.Public.class}) List<SavedFilter> content,
            @JsonView({SavedFilter.View.Public.class}) List<String> sortableBy)
            implements
                Page<SavedFilter>{

        public static SavedFilterPage empty(int page, List<String> sortableBy) {
            return new SavedFilterPage(page, 0, 0, List.of(), sortableBy);
        }
    }
}
