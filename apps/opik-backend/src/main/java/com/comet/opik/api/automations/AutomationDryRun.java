package com.comet.opik.api.automations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/** What a create, update or workflow enable would do, with an estimate from the last 14 days. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AutomationDryRun(Automation automation, Estimate estimate, List<String> warnings) {

    /**
     * How the definition would have behaved over the window: {@code fired} entities carried one of the
     * trigger's score names, {@code matched} satisfied the conditions, and {@code items} is what the
     * per-day cap and the daily limits would have let through.
     */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Estimate(int windowDays, long fired, long matched, long items, BigDecimal costUsd) {
    }
}
