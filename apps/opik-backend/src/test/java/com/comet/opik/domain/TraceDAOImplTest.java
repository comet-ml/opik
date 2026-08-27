package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import com.comet.opik.utils.template.TemplateUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
     * The argMax dedup is gated on two independent conditions: no filter slot may pull in a {@code LEFT JOIN} (a
     * join multiplies row versions inside each group, and a predicate on a joined alias cannot be evaluated by
     * argMax over traces versions), and {@code search_text} must be present (without a heavy per-row scan to
     * amortise it the aggregation state is pure overhead — see OPIK-7636).
     *
     * <p>The gate decides which SQL shape renders, so it is not observable from query results — the two shapes
     * return the same rows by design. That is why it is pinned here rather than black-box; the behaviour the shapes
     * must share is covered by the integration tests instead.
     */
    @Nested
    @DisplayName("argMax dedup guard")
    class ArgMaxDedupGuard {

        @ParameterizedTest(name = "[{index}] {0} -> {1}")
        @CsvSource(delimiter = '|', nullValues = "none", value = {
                // search_text is the enabling condition, on its own and alongside anything harmless
                " search_text                                        | true  ",
                " search_text,filters                                | true  ",
                " search_text,feedback_scores_filters                | true  ",
                " search_text,span_feedback_scores_filters           | true  ",
                " search_text,trace_aggregation_filters              | true  ",
                " search_text,experiment_filters                     | true  ",
                // absent search_text is never enough, whatever else is set
                " none                                               | false ",
                " filters                                            | false ",
                " feedback_scores_filters                            | false ",
                " span_feedback_scores_filters                       | false ",
                " trace_aggregation_filters                          | false ",
                " experiment_filters                                 | false ",
                // a join-bearing slot vetoes even with search_text present
                " search_text,guardrails_filters                     | false ",
                " search_text,feedback_scores_empty_filters          | false ",
                " search_text,span_feedback_scores_empty_filters     | false ",
                " search_text,annotation_queue_filters               | false ",
                " search_text,annotation_queue_id                    | false ",
                " search_text,filters,guardrails_filters             | false ",
        })
        void canDedupByArgMax(String slots, boolean expected) {
            var names = slots == null ? new String[0] : slots.trim().split(",");
            var template = TemplateUtils.newST(
                    Arrays.stream(names).map(slot -> "<" + slot + ">").collect(Collectors.joining()));
            Arrays.stream(names).forEach(slot -> template.add(slot, "anything"));

            assertThat(TraceDAOImpl.canDedupByArgMax(template)).isEqualTo(expected);
        }
    }
}
