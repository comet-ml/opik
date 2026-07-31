package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import com.comet.opik.utils.template.TemplateUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stringtemplate.v4.ST;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TraceDAOImpl get-by-id reducer")
class TraceDAOImplTest {

    @Test
    void firstOrLogFanOut__whenNoRows__returnsEmpty() {
        assertThat(TraceDAOImpl.firstOrLogFanOut(List.of(), UUID.randomUUID(), "ws")).isEmpty();
    }

    @Test
    void firstOrLogFanOut__whenSingleRow__returnsIt() {
        var id = UUID.randomUUID();
        var trace = Trace.builder().id(id).build();

        assertThat(TraceDAOImpl.firstOrLogFanOut(List.of(trace), id, "ws")).containsSame(trace);
    }

    @Test
    void firstOrLogFanOut__whenMoreThanOneRow__returnsFirstWithoutThrowing() {
        var id = UUID.randomUUID();
        var first = Trace.builder().id(id).build();
        var second = Trace.builder().id(id).build();

        // Must not throw IndexOutOfBoundsException ("Source emitted more than one item").
        assertThat(TraceDAOImpl.firstOrLogFanOut(List.of(first, second), id, "ws")).containsSame(first);
    }

    /**
     * The argMax dedup replaces {@code FINAL} in the stats {@code trace_final} CTE. It is gated on two independent
     * conditions, so each is asserted on its own:
     *
     * <ul>
     * <li>no filter slot may pull in a {@code LEFT JOIN} — a join multiplies row versions inside each group, and a
     * predicate on a joined alias cannot be evaluated by argMax over traces versions at all;</li>
     * <li>{@code search_text} must be present — the rewrite builds a full GROUP BY aggregation state before
     * {@code HAVING} can discard anything, so without a heavy per-row scan to amortise it the state is pure
     * overhead and the form is measurably slower and far hungrier for memory (OPIK-7636).</li>
     * </ul>
     *
     * <p>The join-bearing cases therefore set {@code search_text} as well, so a veto proves the join check fired
     * rather than the searchText gate.
     */
    @Nested
    @DisplayName("argMax dedup guard")
    class ArgMaxDedupGuard {

        private ST templateWithSlots(String... slots) {
            var template = TemplateUtils.newST(
                    Arrays.stream(slots).map(slot -> "<" + slot + ">").collect(Collectors.joining()));
            Arrays.stream(slots).forEach(slot -> template.add(slot, "anything"));

            return template;
        }

        @Test
        void canDedupByArgMax__whenSearchTextPresent__isAllowed() {
            assertThat(TraceDAOImpl.canDedupByArgMax(templateWithSlots("search_text"))).isTrue();
        }

        /**
         * No searchText means no scan to amortise the aggregation state, so the plain project-stats query must keep
         * the {@code FINAL} form. This is the case the gate exists for.
         */
        @Test
        void canDedupByArgMax__whenNoSlotSet__isVetoed() {
            assertThat(TraceDAOImpl.canDedupByArgMax(TemplateUtils.newST("noop"))).isFalse();
        }

        /**
         * A filter alone does not earn the rewrite either: a cheap selective predicate is exactly where the
         * aggregation state costs most relative to what it saves.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "filters",
                "feedback_scores_filters",
                "span_feedback_scores_filters",
                "trace_aggregation_filters",
                "experiment_filters"
        })
        void canDedupByArgMax__whenSearchTextAbsent__isVetoed(String slot) {
            assertThat(TraceDAOImpl.canDedupByArgMax(templateWithSlots(slot))).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "guardrails_filters",
                "feedback_scores_empty_filters",
                "span_feedback_scores_empty_filters",
                "annotation_queue_filters",
                "annotation_queue_id"
        })
        void canDedupByArgMax__whenSlotPullsInAJoin__isVetoed(String joinBearingSlot) {
            assertThat(TraceDAOImpl.canDedupByArgMax(templateWithSlots("search_text", joinBearingSlot))).isFalse();
        }

        /**
         * Value-based predicates are what moves into {@code HAVING argMax(...)}, so a filter alongside searchText
         * must not veto the rewrite.
         */
        @Test
        void canDedupByArgMax__whenFiltersAccompanySearchText__isAllowed() {
            assertThat(TraceDAOImpl.canDedupByArgMax(templateWithSlots("search_text", "filters"))).isTrue();
        }

        /**
         * The {@code id IN (...)} slots select whole ids rather than individual rows, so they cannot strand a group
         * on a stale version and stay in {@code WHERE}. They must not veto the rewrite either.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "feedback_scores_filters",
                "span_feedback_scores_filters",
                "trace_aggregation_filters",
                "experiment_filters"
        })
        void canDedupByArgMax__whenIdScopedSlotAccompaniesSearchText__isAllowed(String idScopedSlot) {
            assertThat(TraceDAOImpl.canDedupByArgMax(templateWithSlots("search_text", idScopedSlot))).isTrue();
        }

        @Test
        void canDedupByArgMax__whenJoinBearingSlotAccompaniesSearchTextAndFilters__isVetoed() {
            assertThat(TraceDAOImpl.canDedupByArgMax(
                    templateWithSlots("search_text", "filters", "guardrails_filters"))).isFalse();
        }
    }
}
