package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.Map;

/** A typed answer to one decide question. Probabilities are calibrated by the service. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Answer.NoulAnswer.class, name = "noul"),
        @JsonSubTypes.Type(value = Answer.ChoiceAnswer.class, name = "choice"),
        @JsonSubTypes.Type(value = Answer.ScoreAnswer.class, name = "score")})
public sealed interface Answer {

    /** P(true), in [0, 1]. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record NoulAnswer(double noul) implements Answer {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChoiceAnswer(@NonNull String choice, @NonNull Map<String, Double> probabilities, double confidence)
            implements
                Answer {
    }

    /** {@code score} is the expected level; {@code legend} describes the levels. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScoreAnswer(double score, @NonNull Map<String, JsonNode> legend, @NonNull Map<String, Double> probabilities,
            double confidence) implements Answer {
    }
}
