package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetExperimentItemStreamRequest(
        @Schema(description = "Dataset ID. Set this or dataset_name") UUID datasetId,
        @Schema(description = "Dataset name. Set this or dataset_id") @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String datasetName,
        @Schema(description = "Project of the dataset, used with dataset_name") @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String projectName,
        @Schema(description = "Dataset version hash or tag. Only experiments run against this version are included. Returns 404 if the version does not exist") @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String datasetVersion,
        @Schema(description = "JSON array of dataset item filters, evaluated against the newest version of each item that the experiments reference") String filters,
        @Schema(description = "Maximum number of experiment items to return", defaultValue = "2000") @Min(1) @Max(2000) Integer limit,
        @Schema(description = "experiment_id of the last experiment item received. Set together with last_retrieved_id") UUID lastRetrievedExperimentId,
        @Schema(description = "id of the last experiment item received. Set together with last_retrieved_experiment_id") UUID lastRetrievedId,
        @Schema(description = "Truncate image included in either input, output or metadata", defaultValue = "true") Boolean truncate) {

    private static final int DEFAULT_LIMIT = 2000;

    @Override
    public Integer limit() {
        return limit == null ? DEFAULT_LIMIT : limit;
    }

    @Override
    public Boolean truncate() {
        return truncate == null || truncate;
    }

    @JsonIgnore
    @AssertTrue(message = "exactly one of dataset_id or dataset_name must be set") public boolean isDatasetIdentified() {
        return (datasetId == null) != (datasetName == null);
    }

    @JsonIgnore
    @AssertTrue(message = "last_retrieved_experiment_id and last_retrieved_id must be set together") public boolean isCursorComplete() {
        return (lastRetrievedExperimentId == null) == (lastRetrievedId == null);
    }
}
