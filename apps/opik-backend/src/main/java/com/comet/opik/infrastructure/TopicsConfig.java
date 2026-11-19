package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Topics settings; config.yml describes each key and its default. */
@Data
public class TopicsConfig {

    @Valid @NotNull @JsonProperty
    private AlgorithmConfig algorithm;

    /**
     * The k-means topic algorithm's tunables: quality against run time and prompt length. They depend on the embedding
     * model and the deployment, so they live here; values fixed by the algorithm stay in its code.
     */
    @Data
    public static class AlgorithmConfig {

        @JsonProperty
        @Min(2) @Max(1_000) private int minGroups;

        @JsonProperty
        @Min(2) @Max(1_000) private int maxGroups;

        @JsonProperty
        @Min(1) @Max(10_000) private int textsPerGroup;

        @JsonProperty
        @Min(1) @Max(1_000) private int kmeansMaxIterations;

        @JsonProperty
        @Min(1) @Max(100) private int kmeansRestarts;

        @JsonProperty
        @Min(0) @Max(100) private int authorFrequent;

        @JsonProperty
        @Min(1) @Max(100_000) private int authorBudget;

        @JsonProperty
        @Min(1) @Max(100) private int authorMinExamples;

        @JsonProperty
        @Min(1) @Max(100) private int authorMaxExamples;

        @JsonProperty
        @DecimalMin("0.0") @DecimalMax("1.0") private double assignMinCosine;

        @JsonProperty
        @DecimalMin("0.0") @DecimalMax("1.0") private double otherGate;

        @JsonIgnore
        @AssertTrue(message = "topics.algorithm.minGroups must not be above maxGroups") public boolean isGroupsOrdered() {
            return minGroups <= maxGroups;
        }

        @JsonIgnore
        @AssertTrue(message = "topics.algorithm.authorMinExamples must not be above authorMaxExamples") public boolean isAuthorExamplesOrdered() {
            return authorMinExamples <= authorMaxExamples;
        }
    }
}
