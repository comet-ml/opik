package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Thresholds that turn the decision model's probabilities into guardrail verdicts. Server-side only: the
 * validations endpoint rejects a threshold sent by the caller. The defaults come from the evaluation of
 * 2026-10-21 (design OPIK-8803, section 04).
 */
@Data
@NoArgsConstructor
public class GuardrailsConfig {

    @JsonProperty
    @DecimalMin("0") @DecimalMax("1") private double piiThreshold = 0.5;

    @JsonProperty
    @DecimalMin("0") @DecimalMax("1") private double topicThreshold = 0.3;

    @JsonProperty
    @DecimalMin("0") @DecimalMax("1") private double promptInjectionThreshold = 0.4;
}
