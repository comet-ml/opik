package com.comet.opik.api;

import com.comet.opik.domain.FeedbackDefinitionModel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

public sealed interface AutomationRule<T> permits AutomationRuleEvaluator {

    UUID id();
    UUID projectId();

    AutomationRuleAction action();

    Instant createdAt();
    String createdBy();
    Instant lastUpdatedAt();
    String lastUpdatedBy();

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    enum AutomationRuleAction {

        EVALUATOR("evaluator");

        @JsonValue
        private final String action;

        public static AutomationRule.AutomationRuleAction fromString(String action) {
            return Arrays.stream(values()).filter(v -> v.action.equals(action)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown feedback type: " + action));
        }
    }
}