package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FeedbackScoreGroup(
        @NotBlank String name,
        String categoryName,
        @Schema(description = "Average value of all scores in this group") BigDecimal averageValue,
        @Schema(description = "Minimum value among all scores in this group") BigDecimal minValue,
        @Schema(description = "Maximum value among all scores in this group") BigDecimal maxValue,
        @Schema(description = "Number of scores in this group") int scoreCount,
        @Schema(description = "All individual scores in this group") @NotNull List<FeedbackScore> scores) {
}