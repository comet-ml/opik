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

    /**
     * The guard tests above only assert the boolean. These assert the SQL it actually produces, which is where the
     * risk sits: the grouped subquery's opening {@code FROM (} and its closing {@code )} are emitted from two
     * separate {@code <if(dedup_by_argmax)>} blocks far apart in the template, and the two stats templates
     * deliberately render <em>different</em> argMax shapes — only the traces/spans one wraps, because only it
     * projects columns outside the group key. Both properties are invisible to a boolean assertion.
     */
    @Nested
    @DisplayName("argMax rendered SQL")
    class ArgMaxRenderedSql {

        private String render(String sql, boolean dedupByArgMax) {
            var template = TemplateUtils.newST(sql);
            template.add("search_text", "ilike(name, '%needle%')");
            // SELECT_FEEDBACK_SCORES_STATS renders trace_final only under filters_present, and production sets it
            // from hasAnyTraceFilter, which counts search_text — so the gate always implies it.
            template.add("filters_present", true);
            if (dedupByArgMax) {
                template.add("dedup_by_argmax", true);
            }

            return template.render();
        }

        private long count(String sql, char c) {
            return sql.chars().filter(ch -> ch == c).count();
        }

        @Test
        void tracesSpansStats__whenDedupByArgMax__rendersGroupedSubqueryInsteadOfFinal() {
            var sql = render(TraceDAOImpl.SELECT_TRACES_SPANS_STATS, true);

            assertThat(sql).doesNotContain("FROM traces final");
            assertThat(sql).contains("GROUP BY workspace_id, project_id, id");
            assertThat(sql).contains("HAVING argMax(");
            // the projection is unpacked by name, never by position — see canDedupByArgMax
            assertThat(sql).contains("latest.thread_id AS thread_id");
            assertThat(sql).doesNotContain("latest.1");
        }

        /**
         * The wrapper's {@code FROM (} and its {@code )} come from different template blocks, so a one-sided edit
         * would render unbalanced SQL that only a live query would reject.
         */
        @Test
        void tracesSpansStats__whenDedupByArgMax__rendersBalancedParentheses() {
            var sql = render(TraceDAOImpl.SELECT_TRACES_SPANS_STATS, true);

            assertThat(count(sql, '(')).isEqualTo(count(sql, ')'));
        }

        @Test
        void tracesSpansStats__whenNotDedupByArgMax__keepsFinal() {
            var sql = render(TraceDAOImpl.SELECT_TRACES_SPANS_STATS, false);

            assertThat(sql).contains("FROM traces final");
            assertThat(sql).doesNotContain("GROUP BY workspace_id, project_id, id");
            assertThat(count(sql, '(')).isEqualTo(count(sql, ')'));
        }

        /**
         * Pins the asymmetry between the two templates as a choice rather than an oversight: this one projects only
         * {@code id, project_id}, both group keys, so it needs no tuple and no enclosing SELECT. Adding a projected
         * column here would require the wrapper, and this test is what should fail if that happens.
         */
        @Test
        void feedbackScoresStats__whenDedupByArgMax__groupsWithoutAWrapperSubquery() {
            var sql = render(TraceDAOImpl.SELECT_FEEDBACK_SCORES_STATS, true);

            assertThat(sql).doesNotContain("FROM traces final");
            assertThat(sql).contains("GROUP BY workspace_id, project_id, id");
            assertThat(sql).contains("HAVING argMax(");
            assertThat(sql).doesNotContain("argMax(tuple(");
            assertThat(sql).doesNotContain("latest");
            assertThat(count(sql, '(')).isEqualTo(count(sql, ')'));
        }

        @Test
        void feedbackScoresStats__whenNotDedupByArgMax__keepsFinal() {
            var sql = render(TraceDAOImpl.SELECT_FEEDBACK_SCORES_STATS, false);

            assertThat(sql).contains("FROM traces final");
            assertThat(sql).doesNotContain("GROUP BY workspace_id, project_id, id");
        }
    }
}
