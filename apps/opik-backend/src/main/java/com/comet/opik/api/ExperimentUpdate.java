package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.util.List;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExperimentUpdate(
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String name,
        JsonNode metadata,
        ExperimentType type,
        @Schema(description = "The status of the experiment") ExperimentStatus status,
        @Valid List<ExperimentScore> experimentScores) {
}
