package com.comet.opik.domain;

import com.comet.opik.api.TraceThread;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.infrastructure.FilterUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

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
        // searchClause is only consulted when searchText is set; these cases never set it.
        private static final String UNUSED_SEARCH_CLAUSE = "ilike(thread_id, :search_text)";

        private static ThreadCriteriaAndTemplate render(List<TraceThreadFilter> filters) {
            var criteria = TraceSearchCriteria.builder()
                    .projectId(UUID.randomUUID())
                    .filters(filters)
                    .build();
            var template = FilterUtils.newTraceThreadFindTemplate(
                    ThreadDAOImpl.SELECT_TRACES_THREADS_BY_PROJECT_IDS, criteria, UNUSED_SEARCH_CLAUSE, true);
            return new ThreadCriteriaAndTemplate(criteria, template);
        }

        @Test
        @DisplayName("OPIK-7919: a thread_id EQUAL filter turns the prefilter on and narrows the spans scan")
        void threadIdEqualFilterActivatesPrefilter() {
            var rendered = render(List.of(TraceThreadFilter.builder()
                    .field(TraceThreadField.ID)
                    .operator(Operator.EQUAL)
                    .value("thread-1")
                    .build()));

            // The regression this guards: a thread-ID filter is a TRACE_THREAD-strategy filter, so it never
            // sets "filters". Gating only on "filters" left the prefilter off and spans_deduped scanned the
            // whole project for a single-thread lookup.
            assertThat(rendered.template().getAttribute("filters")).isNull();
            assertThat(rendered.template().getAttribute("traces_pushdown_filter")).isNotNull();

            assertThat(ThreadDAOImpl.shouldUseTracesFinalIdsPrefilter(rendered.criteria(), rendered.template()))
                    .isTrue();

            rendered.template().add("traces_final_ids", true);
            assertThat(rendered.template().render()).contains(SPANS_PREFILTER);
        }

        @Test
        @DisplayName("a TRACE_THREAD filter that is not the id pushdown leaves the prefilter off")
        void nonPushdownThreadFilterDoesNotActivatePrefilter() {
            // status/tags/... are applied by the outer query, not inside traces_final_ids, so enabling the
            // prefilter for them would pay for the extra traces scan without narrowing it.
            var rendered = render(List.of(TraceThreadFilter.builder()
                    .field(TraceThreadField.STATUS)
                    .operator(Operator.EQUAL)
                    .value("active")
                    .build()));

            assertThat(rendered.template().getAttribute("trace_thread_filters")).isNotNull();
            assertThat(rendered.template().getAttribute("traces_pushdown_filter")).isNull();

            assertThat(ThreadDAOImpl.shouldUseTracesFinalIdsPrefilter(rendered.criteria(), rendered.template()))
                    .isFalse();
        }

        @Test
        @DisplayName("no narrowing predicate leaves the prefilter off and the spans scan unrestricted")
        void noNarrowingPredicateLeavesPrefilterOff() {
            var rendered = render(null);

            assertThat(ThreadDAOImpl.shouldUseTracesFinalIdsPrefilter(rendered.criteria(), rendered.template()))
                    .isFalse();
            assertThat(rendered.template().render()).doesNotContain(SPANS_PREFILTER);
        }

        @Test
        @DisplayName("a free-text search still turns the prefilter on")
        void searchTextActivatesPrefilter() {
            var criteria = TraceSearchCriteria.builder()
                    .projectId(UUID.randomUUID())
                    .searchText("needle")
                    .build();
            var template = FilterUtils.newTraceThreadFindTemplate(
                    ThreadDAOImpl.SELECT_TRACES_THREADS_BY_PROJECT_IDS, criteria, UNUSED_SEARCH_CLAUSE, true);

            assertThat(ThreadDAOImpl.shouldUseTracesFinalIdsPrefilter(criteria, template)).isTrue();
        }

        private record ThreadCriteriaAndTemplate(TraceSearchCriteria criteria, org.stringtemplate.v4.ST template) {
        }
    }
}
