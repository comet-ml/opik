package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AssertionResultBatchItem(
        @NonNull @NotNull UUID id,
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") @Schema(description = "If null, the default project is used") String projectName,
        UUID projectId,
        @NonNull @NotBlank String name,
        @NonNull @NotNull Boolean passed,
        String reason,
        @NonNull @NotNull ScoreSource source) {
}
