package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.Comparator;
import java.util.UUID;

/**
 * Reference to an experiment with its ID and name.
 * Used in traces to represent associated experiments.
 * Implements Comparable for natural ordering by experiment name, then by experiment ID.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Experiment reference with ID and name")
public record ExperimentReference(
        @NotNull @Schema(description = "Experiment ID") UUID id,
        @NotNull @Schema(description = "Experiment name") String name)
        implements
            Comparable<ExperimentReference> {

    private static final Comparator<ExperimentReference> COMPARATOR = Comparator
            .comparing(ExperimentReference::name)
            .thenComparing(ExperimentReference::id, Comparator.reverseOrder());

    @Override
    public int compareTo(ExperimentReference other) {
        return COMPARATOR.compare(this, other);
    }
}
