package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueueAutomation.ConditionGroup;
import com.comet.opik.api.AnnotationQueueAutomation.Conditions;
import com.comet.opik.api.AnnotationQueueAutomation.Operator;
import com.comet.opik.api.AnnotationQueueAutomation.ScoreCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Annotation Queue Condition Evaluator")
class AnnotationQueueConditionEvaluatorTest {

    private final AnnotationQueueConditionEvaluator evaluator = new AnnotationQueueConditionEvaluator();

    private static ScoreCondition condition(String score, Operator operator, double value) {
        return ScoreCondition.builder().scoreName(score).operator(operator).value(value).build();
    }

    private static Conditions groups(List<ScoreCondition>... conditionGroups) {
        return Conditions.builder()
                .groups(List.of(conditionGroups).stream()
                        .map(group -> ConditionGroup.builder().conditions(group).build())
                        .toList())
                .build();
    }

    private static Map<String, BigDecimal> scores(Object... nameValuePairs) {
        var result = new java.util.HashMap<String, BigDecimal>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            result.put((String) nameValuePairs[i], BigDecimal.valueOf((Double) nameValuePairs[i + 1]));
        }
        return result;
    }

    @Nested
    @DisplayName("Single condition")
    class SingleCondition {

        @Test
        @DisplayName("greater than: matches strictly above the threshold")
        void greaterThan() {
            var conditions = groups(List.of(condition("hallucination", Operator.GREATER_THAN, 0.7)));

            assertThat(evaluator.matches(conditions, scores("hallucination", 0.9))).isTrue();
            assertThat(evaluator.matches(conditions, scores("hallucination", 0.5))).isFalse();
        }

        @Test
        @DisplayName("boundary value does not match either operator")
        void boundaryIsExclusive() {
            var greater = groups(List.of(condition("hallucination", Operator.GREATER_THAN, 0.7)));
            var less = groups(List.of(condition("hallucination", Operator.LESS_THAN, 0.7)));

            assertThat(evaluator.matches(greater, scores("hallucination", 0.7))).isFalse();
            assertThat(evaluator.matches(less, scores("hallucination", 0.7))).isFalse();
        }

        @Test
        @DisplayName("trailing zeros do not affect comparison")
        void scaleInsensitive() {
            var conditions = groups(List.of(condition("hallucination", Operator.GREATER_THAN, 0.7)));

            assertThat(evaluator.matches(conditions, Map.of("hallucination", new BigDecimal("0.700000000"))))
                    .isFalse();
            assertThat(evaluator.matches(conditions, Map.of("hallucination", new BigDecimal("0.700000001"))))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Missing scores")
    class MissingScores {

        @Test
        @DisplayName("a condition on an absent score does not match")
        void absentScoreDoesNotMatch() {
            var conditions = groups(List.of(condition("hallucination", Operator.GREATER_THAN, 0.7)));

            assertThat(evaluator.matches(conditions, Map.of())).isFalse();
            assertThat(evaluator.matches(conditions, scores("answer_relevance", 0.9))).isFalse();
        }

        @Test
        @DisplayName("a conjunction only matches once its last score arrives")
        void conjunctionCompletesWhenLastScoreLands() {
            var conditions = groups(List.of(
                    condition("hallucination", Operator.GREATER_THAN, 0.7),
                    condition("answer_relevance", Operator.LESS_THAN, 0.3)));

            // The judge score lands first; answer_relevance is still missing.
            assertThat(evaluator.matches(conditions, scores("hallucination", 0.9))).isFalse();

            // A human scores answer_relevance hours later, completing the AND.
            assertThat(evaluator.matches(conditions, scores("hallucination", 0.9, "answer_relevance", 0.1)))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Groups")
    class Groups {

        @Test
        @DisplayName("conditions within a group are ANDed")
        void withinGroupIsAnd() {
            var conditions = groups(List.of(
                    condition("hallucination", Operator.GREATER_THAN, 0.7),
                    condition("answer_relevance", Operator.LESS_THAN, 0.3)));

            assertThat(evaluator.matches(conditions, scores("hallucination", 0.9, "answer_relevance", 0.1)))
                    .isTrue();
            // Second condition fails.
            assertThat(evaluator.matches(conditions, scores("hallucination", 0.9, "answer_relevance", 0.8)))
                    .isFalse();
        }

        @Test
        @DisplayName("groups are ORed, so any satisfied group is enough")
        void acrossGroupsIsOr() {
            var conditions = groups(
                    List.of(condition("hallucination", Operator.GREATER_THAN, 0.7)),
                    List.of(condition("moderation", Operator.GREATER_THAN, 0.9)));

            assertThat(evaluator.matches(conditions, scores("hallucination", 0.9))).isTrue();
            assertThat(evaluator.matches(conditions, scores("moderation", 0.95))).isTrue();
            assertThat(evaluator.matches(conditions, scores("hallucination", 0.1, "moderation", 0.1)))
                    .isFalse();
        }

        @Test
        @DisplayName("one unsatisfiable group does not veto a satisfied sibling")
        void missingScoreInOneGroupDoesNotVetoAnother() {
            var conditions = groups(
                    List.of(condition("never_scored", Operator.GREATER_THAN, 0.5)),
                    List.of(condition("moderation", Operator.GREATER_THAN, 0.9)));

            assertThat(evaluator.matches(conditions, scores("moderation", 0.95))).isTrue();
        }
    }

    @Nested
    @DisplayName("Degenerate input")
    class DegenerateInput {

        @Test
        @DisplayName("no groups matches nothing")
        void noGroups() {
            var conditions = Conditions.builder().groups(List.of()).build();

            assertThat(evaluator.matches(conditions, scores("hallucination", 0.9))).isFalse();
        }

        @Test
        @DisplayName("an empty group matches nothing rather than everything")
        void emptyGroup() {
            var conditions = Conditions.builder()
                    .groups(List.of(ConditionGroup.builder().conditions(List.of()).build()))
                    .build();

            assertThat(evaluator.matches(conditions, scores("hallucination", 0.9))).isFalse();
        }
    }
}
