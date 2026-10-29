package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.concurrent.TimeUnit;

/**
 * Guardrail checks on opik-inference: the thresholds that turn the decision model's probabilities into verdicts, and
 * the time the service may spend on one check. Server-side only: the validations endpoint rejects a threshold sent
 * by the caller. The defaults come from the evaluation of 2026-10-21 (design OPIK-8803, section 04). The service's
 * URL and credential are the shared {@code aiLabels.inference} settings.
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

    /**
     * Sent as {@code deadline_ms}: how long the decision service may spend on one check. The realtime lane caps it
     * at 5 s. The API default of 150 ms is below the measured p99 of a seven-question call under load, so the value
     * here must be set explicitly.
     */
    @JsonProperty
    @NotNull @MinDuration(value = 10, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 5, unit = TimeUnit.SECONDS)
    private Duration deadline = Duration.seconds(1);
}
