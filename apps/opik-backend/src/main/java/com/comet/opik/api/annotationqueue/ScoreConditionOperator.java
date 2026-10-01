package com.comet.opik.api.annotationqueue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/**
 * {@code EQUAL} is for categorical scores — a boolean written as 0/1, or a rating coded as an integer.
 * Note what it compares: the <em>effective</em> score, which is averaged across authors, so an equality
 * that matches while one annotator has scored an item can stop matching once a second one disagrees
 * (1 and 0 average to 0.5). Exact matching is dependable where a single author writes the score, which
 * is the case for LLM judges and SDK-written scores.
 */
@Getter
@RequiredArgsConstructor
public enum ScoreConditionOperator {

    GREATER_THAN(">"),
    LESS_THAN("<"),
    EQUAL("=");

    @JsonValue
    private final String value;

    @JsonCreator
    public static ScoreConditionOperator fromString(String value) {
        return Arrays.stream(values())
                .filter(operator -> operator.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown score condition operator '%s'".formatted(value)));
    }
}
