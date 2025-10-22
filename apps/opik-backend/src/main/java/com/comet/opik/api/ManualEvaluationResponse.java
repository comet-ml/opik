package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * Response model for manual evaluation requests.
 * Provides confirmation details about the evaluation job that was queued.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ManualEvaluationResponse(
        @Schema(description = "Number of entities queued for evaluation", example = "5") int entitiesQueued,
        @Schema(description = "Number of rules that will be applied", example = "2") int rulesApplied,
        @Schema(description = "Confirmation message", example = "Successfully queued 5 entities for evaluation with 2 rules") String message) {

    /**
     * Creates a ManualEvaluationResponse with a generated message.
     *
     * @param entitiesQueued Number of entities queued for evaluation
     * @param rulesApplied Number of rules applied
     * @return ManualEvaluationResponse with generated message
     */
    public static ManualEvaluationResponse of(int entitiesQueued, int rulesApplied) {
        String message = "Successfully queued %d %s for evaluation with %d %s"
                .formatted(
                        entitiesQueued,
                        entitiesQueued == 1 ? "entity" : "entities",
                        rulesApplied,
                        rulesApplied == 1 ? "rule" : "rules");
        return new ManualEvaluationResponse(entitiesQueued, rulesApplied, message);
    }
}
