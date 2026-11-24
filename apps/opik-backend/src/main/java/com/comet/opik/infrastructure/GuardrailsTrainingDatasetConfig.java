package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The limits a guardrail model's training data is held to when a member uploads it. The trainer on opik-inference
 * applies the same rules and reads the same environment variables, so both sides agree on what a valid file is.
 */
@Data
@NoArgsConstructor
public class GuardrailsTrainingDatasetConfig {

    @JsonProperty
    @Min(1) private int maxFileSizeMb = 50;

    @JsonProperty
    @Min(1) private int minRows = 20;

    @JsonProperty
    @Min(1) private int maxRows = 50_000;

    @JsonProperty
    @Min(1) private int minRowsPerLabel = 5;

    // Bounded so that the longest line a valid row can take, which the validator reads up to, stays a few MB.
    @JsonProperty
    @Min(1) @Max(100_000) private int maxTextLength = 12_000;
}
