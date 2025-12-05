package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Dataset(
        @JsonView( {
                Dataset.View.Public.class, Dataset.View.Write.class}) UUID id,
        @JsonView({Dataset.View.Public.class, Dataset.View.Write.class}) @NotBlank String name,
        @JsonView({Dataset.View.Public.class, Dataset.View.Write.class}) Visibility visibility,
        @JsonView({Dataset.View.Public.class, Dataset.View.Write.class}) Set<String> tags,
        @JsonView({Dataset.View.Public.class,
                Dataset.View.Write.class}) @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Size(max = 255, message = "cannot exceed 255 characters") String description,
        @JsonView({Dataset.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({Dataset.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({Dataset.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({Dataset.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy,
        @JsonView({
                Dataset.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Long experimentCount,
        @JsonView({
                Dataset.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Long datasetItemsCount,
        @JsonView({
                Dataset.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Long optimizationCount,
        @JsonView({
                Dataset.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Instant mostRecentExperimentAt,
        @JsonView({
                Dataset.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Instant lastCreatedExperimentAt,
        @JsonView({
                Dataset.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Instant mostRecentOptimizationAt,
        @JsonView({
                Dataset.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Instant lastCreatedOptimizationAt,
        @JsonView({
                Dataset.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable DatasetStatus status,
        @JsonView({
                Dataset.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Summary of the latest version of this dataset") @Nullable DatasetVersionSummary latestVersion){

    public static class View {

        public static class Public {
        }

        public static class Write {
        }
    }

    @Builder(toBuilder = true)
    public record DatasetPage(
            @JsonView( {
                    Dataset.View.Public.class}) List<Dataset> content,
            @JsonView({Dataset.View.Public.class}) int page,
            @JsonView({Dataset.View.Public.class}) int size,
            @JsonView({Dataset.View.Public.class}) long total,
            @JsonView({Dataset.View.Public.class}) List<String> sortableBy) implements Page<Dataset>{

        public static DatasetPage empty(int page, List<String> sortableBy) {
            return new DatasetPage(List.of(), page, 0, 0, sortableBy);
        }
    }
}
