package com.comet.opik.domain.ailabels;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * One row of {@code ai_labels}: one value of one (trace, task). {@code key} is a question id without its task prefix,
 * or {@code output} for a generate task. Only the columns of its {@code answerType} are set.
 */
@Builder(toBuilder = true)
public record AiLabel(
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull String task,
        @NonNull UUID traceId,
        @NonNull String key,
        int taskVersion,
        @NonNull AnswerType answerType,
        Float noul,
        Float score,
        String choice,
        Float confidence,
        @NonNull Map<String, Float> probabilities,
        @NonNull Map<String, String> legend,
        @NonNull String output) {

    public static final String OUTPUT_KEY = "output";

    @Getter
    @RequiredArgsConstructor
    public enum AnswerType {
        NOUL("noul"),
        CHOICE("choice"),
        SCORE("score"),
        OUTPUT("output");

        @JsonValue
        private final String value;
    }
}
