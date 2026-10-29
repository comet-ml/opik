package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A decide question. {@link #validate()} applies the service's own rules (the Python client's models), so a question
 * that passes it is never the reason the service rejects a job.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Question.NoulQuestion.class, name = "noul"),
        @JsonSubTypes.Type(value = Question.ChoiceQuestion.class, name = "choice"),
        @JsonSubTypes.Type(value = Question.ScoreQuestion.class, name = "score")})
public sealed interface Question {

    Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    JsonNode instructions();

    /** @throws IllegalArgumentException when the service would reject this question */
    void validate();

    private static void requireContent(JsonNode value, String what) {
        if (value == null || value.isNull() || (value.isTextual() && value.textValue().isEmpty())
                || (value.isContainerNode() && value.isEmpty())) {
            throw new IllegalArgumentException("%s must not be empty".formatted(what));
        }
    }

    @Builder(toBuilder = true)
    record NoulQuestion(@NonNull JsonNode instructions, NoulCriteria criteria) implements Question {

        @Override
        public void validate() {
            requireContent(instructions, "instructions");
        }
    }

    /** Optional descriptions of what a yes (near 1) and a no (near 0) mean. */
    @Builder(toBuilder = true)
    record NoulCriteria(@JsonProperty("true") JsonNode whenTrue, @JsonProperty("false") JsonNode whenFalse) {
    }

    @Builder(toBuilder = true)
    record ChoiceQuestion(@NonNull JsonNode instructions, @NonNull Map<String, JsonNode> criteria)
            implements
                Question {

        public static final int MAX_OPTIONS = 255;

        @Override
        public void validate() {
            requireContent(instructions, "instructions");
            if (criteria.isEmpty() || criteria.size() > MAX_OPTIONS) {
                throw new IllegalArgumentException(
                        "choice criteria must have 1 to '%d' options, options '%d'".formatted(MAX_OPTIONS,
                                criteria.size()));
            }
            if (criteria.keySet().stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("choice options must be non-empty");
            }
        }
    }

    @Builder(toBuilder = true)
    record ScoreQuestion(@NonNull JsonNode instructions, @NonNull List<JsonNode> criteria) implements Question {

        public static final int MIN_LEVELS = 2;
        public static final int MAX_LEVELS = 10;

        @Override
        public void validate() {
            requireContent(instructions, "instructions");
            if (criteria.size() < MIN_LEVELS || criteria.size() > MAX_LEVELS) {
                throw new IllegalArgumentException("score criteria must have '%d' to '%d' levels, levels '%d'"
                        .formatted(MIN_LEVELS, MAX_LEVELS, criteria.size()));
            }
        }
    }
}
