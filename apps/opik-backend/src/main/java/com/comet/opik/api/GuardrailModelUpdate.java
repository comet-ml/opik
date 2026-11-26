package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/** What is absent stays as it is. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GuardrailModelUpdate(
        @Schema(description = "A new note for people; a blank one removes the note") @Size(max = GuardrailModel.DESCRIPTION_MAX_LENGTH, message = "cannot exceed {max} characters") String description,
        @Schema(description = "A new config, replacing the current one whole; it applies from the next check, with no retrain") @Valid GuardrailModelConfig config) {
}
