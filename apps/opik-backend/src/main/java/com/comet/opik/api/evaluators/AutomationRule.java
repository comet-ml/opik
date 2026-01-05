package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.SortedSet;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "action", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AutomationRuleEvaluator.class, name = "evaluator")
})
@Schema(name = "AutomationRule", discriminatorProperty = "action", discriminatorMapping = {
        @DiscriminatorMapping(value = "evaluator", schema = AutomationRuleEvaluator.class)
})
public sealed interface AutomationRule permits AutomationRuleEvaluator {

    UUID getId();

    // Dual-field architecture for backward compatibility
    UUID getProjectId(); // Legacy - derived from first project
    String getProjectName(); // Legacy - derived from first project
    SortedSet<ProjectReference> getProjects(); // Primary field (unique, sorted alphabetically by name)

    String getName();

    AutomationRuleAction getAction();
    float getSamplingRate();
    boolean isEnabled();

    Instant getCreatedAt();
    String getCreatedBy();
    Instant getLastUpdatedAt();
    String getLastUpdatedBy();

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    enum AutomationRuleAction {

        EVALUATOR("evaluator");

        @JsonValue
        private final String action;

        public static AutomationRule.AutomationRuleAction fromString(String action) {
            return Arrays.stream(values())
                    .filter(v -> v.action.equals(action)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown rule type: " + action));
        }
    }
}
