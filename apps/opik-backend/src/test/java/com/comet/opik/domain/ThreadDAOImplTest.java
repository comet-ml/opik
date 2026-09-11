package com.comet.opik.domain;

import com.comet.opik.api.TraceThread;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.infrastructure.FilterUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadDAOImplTest {

    @Test
    @DisplayName("a multi-element stream collapses to the first thread (singleOrEmpty would throw here)")
    void firstThreadOrEmptyCollapsesMultipleEmissions() {
        var first = TraceThread.builder().id("thread-1").build();
        var second = TraceThread.builder().id("thread-2").build();

        StepVerifier.create(ThreadDAOImpl.firstThreadOrEmpty(Flux.just(first, second)))
                .assertNext(thread -> assertThat(thread.id()).isEqualTo("thread-1"))
                .verifyComplete();
    }

    @Test
    @DisplayName("a single emitted thread passes through unchanged")
    void firstThreadOrEmptyPassesSingleThreadThrough() {
        var thread = TraceThread.builder().id("thread-1").build();

        StepVerifier.create(ThreadDAOImpl.firstThreadOrEmpty(Flux.just(thread)))
                .assertNext(result -> assertThat(result.id()).isEqualTo("thread-1"))
                .verifyComplete();
    }

    @Test
    @DisplayName("an empty stream completes empty (preserving the not-found contract)")
    void firstThreadOrEmptyCompletesEmptyForEmptyStream() {
        StepVerifier.create(ThreadDAOImpl.firstThreadOrEmpty(Flux.empty()))
                .verifyComplete();
    }

    @Nested
    @DisplayName("traces_final_ids prefilter gate")
    class TracesFinalIdsPrefilterGate {

        private static final String SPANS_PREFILTER = "AND trace_id IN (SELECT id FROM traces_final_ids)";
        // Rendered into the query only when searchText is set (FilterUtils#newTraceThreadFindTemplate);
        // the searchText case below is the one that exercises it.
        private static final String SEARCH_CLAUSE = "ilike(thread_id, :search_text)";

        /**
         * Mirrors the production wiring in {@link ThreadDAOImpl#search}: the {@code traces_final_ids}
         * attribute is added only when the gate says so, so the rendered SQL asserted below is a function
         * of the gate's result rather than of the test's own setup.
         */
        private static Rendered render(List<TraceThreadFilter> filters, String searchText) {
            var criteria = TraceSearchCriteria.builder()
                    .projectId(UUID.randomUUID())
                    .filters(filters)
                    .searchText(searchText)
                    .build();
            var template = FilterUtils.newTraceThreadFindTemplate(
                    ThreadDAOImpl.SELECT_TRACES_THREADS_BY_PROJECT_IDS, criteria, SEARCH_CLAUSE, true);

            boolean gateOpen = ThreadDAOImpl.shouldUseTracesFinalIdsPrefilter(criteria, template);
            if (gateOpen) {
                template.add("traces_final_ids", true);
            }
            return new Rendered(template, gateOpen);
        }

        private static Rendered renderWithFilter(TraceThreadField field, Operator operator, String value) {
            return render(List.of(TraceThreadFilter.builder()
                    .field(field)
                    .operator(operator)
                    .value(value)
                    .build()), null);
        }

        @Test
        @DisplayName("OPIK-7919: a thread_id EQUAL filter turns the prefilter on and narrows the spans scan")
        void threadIdEqualFilterActivatesPrefilter() {
            var rendered = renderWithFilter(TraceThreadField.ID, Operator.EQUAL, "thread-1");

            // The regression this guards: a thread-ID filter is a TRACE_THREAD-strategy filter, so it never
            // sets "filters". Gating only on "filters" left the prefilter off and spans_deduped scanned the
            // whole project for a single-thread lookup.
            assertThat(rendered.template().getAttribute("filters")).isNull();
            assertThat(rendered.template().getAttribute("traces_pushdown_filter")).isNotNull();

            assertThat(rendered.gateOpen()).isTrue();
            assertThat(rendered.template().render()).contains(SPANS_PREFILTER);
        }

        @Test
        @DisplayName("an ID filter with a non-EQUAL operator does not reach the pushdown or the prefilter")
        void threadIdContainsFilterDoesNotActivatePrefilter() {
            // findTraceThreadIdPushdownFilter is EQUAL-only because the pushdown SQL is hardcoded to
            // `thread_id = :thread_id_pushdown`; a CONTAINS filter answered as an equality would be wrong.
            var rendered = renderWithFilter(TraceThreadField.ID, Operator.CONTAINS, "thread");

            assertThat(rendered.template().getAttribute("traces_pushdown_filter")).isNull();
            assertThat(rendered.gateOpen()).isFalse();
            assertThat(rendered.template().render()).doesNotContain(SPANS_PREFILTER);
        }

        @Test
        @DisplayName("a TRACE_THREAD filter that is not the id pushdown leaves the prefilter off")
        void nonPushdownThreadFilterDoesNotActivatePrefilter() {
            // status/tags/... are applied by the outer query, not inside traces_final_ids, so enabling the
            // prefilter for them would pay for the extra traces scan without narrowing it.
            var rendered = renderWithFilter(TraceThreadField.STATUS, Operator.EQUAL, "active");

            assertThat(rendered.template().getAttribute("trace_thread_filters")).isNotNull();
            assertThat(rendered.template().getAttribute("traces_pushdown_filter")).isNull();

            assertThat(rendered.gateOpen()).isFalse();
            assertThat(rendered.template().render()).doesNotContain(SPANS_PREFILTER);
        }

        @Test
        @DisplayName("no narrowing predicate leaves the prefilter off and the spans scan unrestricted")
        void noNarrowingPredicateLeavesPrefilterOff() {
            var rendered = render(null, null);

            assertThat(rendered.gateOpen()).isFalse();
            assertThat(rendered.template().render()).doesNotContain(SPANS_PREFILTER);
        }

        @Test
        @DisplayName("a free-text search satisfies the gate (the listing path may still prefer the page-pushdown)")
        void searchTextSatisfiesTheGate() {
            // Scoped to the gate on purpose: in ThreadDAOImpl#find a search-only criteria is page-pushdown
            // eligible ("search_text" is not a PAGE_PUSHDOWN_DISQUALIFIER), so the listing query never
            // reaches this branch. The gate result still holds for search/stats/count.
            var rendered = render(null, "needle");

            assertThat(rendered.gateOpen()).isTrue();
            assertThat(rendered.template().render()).contains(SPANS_PREFILTER);
        }

        private record Rendered(ST template, boolean gateOpen) {
        }
    }

    @Nested
    @DisplayName("thread_id pushdown across the templates that share it")
    class ThreadIdPushdownTemplateSync {

        private static final String THREAD_ID_PUSHDOWN = "AND thread_id = :thread_id_pushdown";
        private static final String TRACES_FINAL_IDS_IN = "thread_id IN (SELECT thread_id FROM traces_final_ids)";
        private static final String SEARCH_CLAUSE = "ilike(thread_id, :search_text)";

        static Stream<Arguments> templatesSharingThePushdown() {
            return Stream.of(
                    Arguments.of("list", ThreadDAOImpl.SELECT_TRACES_THREADS_BY_PROJECT_IDS),
                    Arguments.of("count", ThreadDAOImpl.SELECT_COUNT_TRACES_THREADS_BY_PROJECT_IDS),
                    Arguments.of("stats", ThreadDAOImpl.SELECT_TRACE_THREADS_STATS));
        }

        /**
         * OPIK-7919: on the uuid_from_time branch trace_threads_final skips the traces_final_ids IN, so the
         * thread_id equality is the only predicate left that prunes — and trace_threads is
         * ORDER BY (workspace_id, project_id, thread_id, id), so it prunes on the primary key while
         * {@code id >= :uuid_from_time} cannot. The count template was missing this line while the list and
         * stats templates had it, which left countThreadTotal scanning every trace_threads row of the
         * project. This pins all three templates to emit it identically.
         */
        @ParameterizedTest(name = "{0} template")
        @MethodSource("templatesSharingThePushdown")
        @DisplayName("trace_threads_final emits the thread_id pushdown on the uuid_from_time branch")
        void traceThreadsFinalEmitsThreadIdPushdownOnUuidBranch(String name, String query) {
            var criteria = TraceSearchCriteria.builder()
                    .projectId(UUID.randomUUID())
                    .uuidFromTime(UUID.randomUUID())
                    .filters(List.of(TraceThreadFilter.builder()
                            .field(TraceThreadField.ID)
                            .operator(Operator.EQUAL)
                            .value("thread-1")
                            .build()))
                    .build();

            var template = FilterUtils.newTraceThreadFindTemplate(query, criteria, SEARCH_CLAUSE, true);
            if (ThreadDAOImpl.shouldUseTracesFinalIdsPrefilter(criteria, template)) {
                template.add("traces_final_ids", true);
            }

            var traceThreadsFinal = traceThreadsFinalCte(template.render());

            assertThat(traceThreadsFinal).contains("AND id >= :uuid_from_time");
            assertThat(traceThreadsFinal).doesNotContain(TRACES_FINAL_IDS_IN);
            assertThat(traceThreadsFinal).contains(THREAD_ID_PUSHDOWN);
        }

        /** The trace_threads_final CTE body, up to its ORDER BY — so the assertions cannot match another CTE. */
        private static String traceThreadsFinalCte(String sql) {
            int start = sql.indexOf("trace_threads_final AS (");
            assertThat(start).isNotNegative();
            int end = sql.indexOf("ORDER BY (workspace_id, project_id, thread_id, id)", start);
            assertThat(end).isGreaterThan(start);
            return sql.substring(start, end);
        }
    }
}
