package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

public sealed interface AutomationRule<T> permits AutomationRuleEvaluator {

    UUID id();
    UUID projectId();

    AutomationRuleAction action();
    float samplingRate();

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