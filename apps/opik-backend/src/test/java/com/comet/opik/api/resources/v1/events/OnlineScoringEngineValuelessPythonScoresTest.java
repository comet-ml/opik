package com.comet.opik.api.resources.v1.events;

import com.comet.opik.domain.evaluators.python.PythonScoreResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Covers the log-safety half of the shared valueless-score reporting: the names come from a user's Python
 * metric and the thread entity id from the caller, so the warning must not be forgeable, floodable by a
 * single evaluation, or floodable by one oversized name. Asserted once here rather than in each of the
 * three scorer suites, which share this helper.
 */
class OnlineScoringEngineValuelessPythonScoresTest {

    private static final Map<String, String> MDC = Map.of("workspace_id", "workspace-1", "rule_id", "rule-1");

    private final Logger userFacingLogger = mock(Logger.class);

    private Object logAndCaptureNames(List<String> valuelessNames, String entityLabel, Object entityId) {
        OnlineScoringEngine.logValuelessPythonScores(userFacingLogger, MDC, valuelessNames, entityLabel, entityId);

        var names = org.mockito.ArgumentCaptor.forClass(Object.class);
        var label = org.mockito.ArgumentCaptor.forClass(Object.class);
        var id = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(userFacingLogger).warn(anyString(), names.capture(), label.capture(), id.capture());
        return names.getValue();
    }

    @Nested
    class SplittingTests {

        @Test
        void keepsScoresWithAValueAndCollectsTheNamesOfTheOnesWithout() {
            var valued = PythonScoreResult.builder().name("relevance").value(BigDecimal.ONE).build();
            var valueless = PythonScoreResult.builder().name("hallucination").build();

            var split = OnlineScoringEngine.toStorablePythonScores(List.of(valued, valueless));

            assertThat(split.storable()).containsExactly(valued);
            assertThat(split.valuelessNames()).containsExactly("hallucination");
        }

        @Test
        void countsAScoreWithNoNameAmongTheDropped() {
            // A metric can leave a score unnamed. The collected names go through List.copyOf, which rejects
            // a null element — so an unnamed valueless score used to fail the batch from inside this helper.
            var unnamed = PythonScoreResult.builder().build();
            var valued = PythonScoreResult.builder().name("relevance").value(BigDecimal.ONE).build();

            var split = OnlineScoringEngine.toStorablePythonScores(List.of(valued, unnamed));

            assertThat(split.storable()).containsExactly(valued);
            assertThat(split.valuelessNames()).containsExactly("");
        }

        @Test
        void countsANullEntryAmongTheDroppedWithoutDereferencingIt() {
            // A JSON `null` in the evaluator's array deserializes to a null element.
            var valued = PythonScoreResult.builder().name("relevance").value(BigDecimal.ONE).build();

            var split = OnlineScoringEngine.toStorablePythonScores(Arrays.asList(valued, null));

            assertThat(split.storable()).containsExactly(valued);
            assertThat(split.valuelessNames()).containsExactly("");
        }

        @Test
        void treatsZeroAsAValue() {
            // BigDecimal.ZERO is a legitimate score, not a missing one — a metric answering "no" must store.
            var zero = PythonScoreResult.builder().name("is_toxic").value(BigDecimal.ZERO).build();

            var split = OnlineScoringEngine.toStorablePythonScores(List.of(zero));

            assertThat(split.storable()).containsExactly(zero);
            assertThat(split.valuelessNames()).isEmpty();
        }
    }

    @Nested
    class LogSafetyTests {

        @Test
        void doesNotLogWhenNothingWasDropped() {
            OnlineScoringEngine.logValuelessPythonScores(userFacingLogger, MDC, List.of(), "traceId",
                    UUID.randomUUID());

            verify(userFacingLogger, never()).warn(anyString(), org.mockito.ArgumentMatchers.<Object[]>any());
        }

        @Test
        void replacesLineBreaksInAScoreNameWithSpaces() {
            var forged = "ok\nERROR [2026-01-01 00:00:00,000] forged entry";

            var rendered = logAndCaptureNames(List.of(forged), "traceId", UUID.randomUUID());

            assertThat(rendered).asString().doesNotContain("\n").doesNotContain("\r").contains("ok ERROR");
        }

        @Test
        void capsAnOversizedScoreName() {
            var rendered = logAndCaptureNames(List.of("x".repeat(500)), "traceId", UUID.randomUUID());

            // 100 chars of name plus the ellipsis and the quotes the renderer adds.
            assertThat(rendered).asString().hasSizeLessThan(150).endsWith("…'");
        }

        @Test
        void capsTheNumberOfReportedNamesAndCountsTheRemainder() {
            var names = IntStream.range(0, 25).mapToObj("score_%d"::formatted).toList();

            var rendered = logAndCaptureNames(names, "traceId", UUID.randomUUID());

            assertThat(rendered).asString().contains("'score_0'").contains("'score_9'")
                    .doesNotContain("'score_10'").contains("and 15 more");
        }

        @Test
        void rendersAnUnnamedScoreRatherThanDroppingIt() {
            var rendered = logAndCaptureNames(List.of(""), "traceId", UUID.randomUUID());

            assertThat(rendered).asString().contains("<unnamed>");
        }

        @Test
        void rendersAScoreWhoseNameIsNullRatherThanFailing() {
            var rendered = logAndCaptureNames(Arrays.asList((String) null), "traceId", UUID.randomUUID());

            assertThat(rendered).asString().contains("<unnamed>");
        }

        @Test
        void replacesLineBreaksInTheEntityId() {
            // The thread scorer passes a caller-supplied thread id, so this one is not always a UUID.
            var forged = "thread-1\nERROR [2026-01-01 00:00:00,000] forged entry";

            OnlineScoringEngine.logValuelessPythonScores(userFacingLogger, MDC, List.of("hallucination"),
                    "threadId", forged);

            var names = org.mockito.ArgumentCaptor.forClass(Object.class);
            var label = org.mockito.ArgumentCaptor.forClass(Object.class);
            var id = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(userFacingLogger).warn(anyString(), names.capture(), label.capture(), id.capture());

            assertThat(id.getValue()).asString().doesNotContain("\n").doesNotContain("\r")
                    .contains("thread-1 ERROR");
        }
    }
}
