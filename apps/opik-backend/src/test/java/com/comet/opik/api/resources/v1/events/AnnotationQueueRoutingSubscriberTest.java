package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueItemSource;
import com.comet.opik.api.Source;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.domain.AnnotationQueueAutomationService;
import com.comet.opik.domain.AnnotationQueueAutomationService.QueueAutomation;
import com.comet.opik.domain.AnnotationQueueRoutingMessage;
import com.comet.opik.domain.AnnotationQueueService;
import com.comet.opik.domain.ThreadDAO;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.domain.TraceSearchCriteria;
import com.comet.opik.domain.threads.TraceThreadDAO;
import com.comet.opik.domain.threads.TraceThreadIdModel;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import com.comet.opik.infrastructure.FeatureFlags;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers which entities the routing consumer routes, and how it asks the traces and threads queries for
 * them, which is what decides what a reviewer ends up being asked to look at.
 */
@ExtendWith(MockitoExtension.class)
class AnnotationQueueRoutingSubscriberTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();

    @Mock
    private RedissonReactiveClient redisson;
    @Mock
    private FeatureFlags featureFlags;
    @Mock
    private AnnotationQueueAutomationService automationService;
    @Mock
    private AnnotationQueueService annotationQueueService;
    @Mock
    private TraceDAO traceDAO;
    @Mock
    private ThreadDAO threadDAO;
    @Mock
    private TraceThreadDAO traceThreadDAO;
    @Mock
    private TraceThreadService traceThreadService;

    private UUID projectId;
    private UUID queueId;
    private Filter scoreFilter;
    private AnnotationQueueRoutingSubscriber subscriber;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        queueId = UUID.randomUUID();
        scoreFilter = TraceFilter.builder()
                .field(TraceField.FEEDBACK_SCORES)
                .operator(Operator.LESS_THAN)
                .key(RandomStringUtils.secure().nextAlphanumeric(10))
                .value("0.5")
                .build();
        var config = AnnotationQueueRoutingConfig.builder()
                .consumerBatchSize(10)
                .searchConcurrency(4)
                .build();
        subscriber = new AnnotationQueueRoutingSubscriber(config, redisson, featureFlags, automationService,
                annotationQueueService, traceDAO, threadDAO, traceThreadDAO, traceThreadService);
    }

    @Nested
    @DisplayName("Filter matching")
    class FilterMatchingTests {

        @Test
        void asksTheTracesSearchForTheAutomationFiltersNarrowedToTheBatch() {
            UUID matching = UUID.randomUUID();
            UUID notMatching = UUID.randomUUID();
            givenTraces(matching, notMatching);
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.TRACE, List.of(List.of(scoreFilter)));
            givenLoggingSource(matching, notMatching);
            when(traceDAO.search(anyInt(), any())).thenReturn(Flux.just(trace(matching)));
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(1L));

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(matching, notMatching));

            var criteria = ArgumentCaptor.forClass(TraceSearchCriteria.class);
            verify(traceDAO).search(eq(2), criteria.capture());
            assertThat(criteria.getValue().projectId()).isEqualTo(projectId);
            assertThat(criteria.getValue().filters()).isEqualTo(List.of(scoreFilter));
            assertThat(criteria.getValue().traceIds()).containsExactlyInAnyOrder(matching, notMatching);

            verify(annotationQueueService).addItems(queueId, Set.of(matching), AnnotationQueueItemSource.AUTOMATED);
        }

        @Test
        void addsNothingWhenNoEntityMatches() {
            UUID traceId = UUID.randomUUID();
            givenTraces(traceId);
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.TRACE, List.of(List.of(scoreFilter)));
            givenLoggingSource(traceId);
            when(traceDAO.search(anyInt(), any())).thenReturn(Flux.empty());

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId));

            verifyNoInteractions(annotationQueueService);
        }

        /**
         * Filter groups are OR-ed: an entity matching any of them is routed.
         */
        @Test
        void routesWhatAnyFilterGroupMatches() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            Filter otherFilter = scoreFilter.build("0.9");
            givenTraces(first, second);
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.TRACE,
                    List.of(List.of(scoreFilter), List.of(otherFilter)));
            givenLoggingSource(first, second);
            when(traceDAO.search(anyInt(), any())).thenReturn(Flux.just(trace(first)), Flux.just(trace(second)));
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(2L));

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(first, second));

            verify(annotationQueueService).addItems(queueId, Set.of(first, second),
                    AnnotationQueueItemSource.AUTOMATED);
        }

        /**
         * Automations of one project often share filters; each search is a ClickHouse query, so a group is searched
         * once and its matches reach every queue that uses it.
         */
        @Test
        void searchesASharedFilterGroupOnceForEveryQueue() {
            UUID traceId = UUID.randomUUID();
            UUID secondQueueId = UUID.randomUUID();
            givenMatchingTrace(traceId);
            when(automationService.findEnabledByProjects(WORKSPACE_ID, Set.of(projectId),
                    AnnotationQueue.AnnotationScope.TRACE))
                    .thenReturn(List.of(automation(List.of(List.of(scoreFilter))),
                            automation(List.of(List.of(scoreFilter))).toBuilder().queueId(secondQueueId).build()));
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(1L));

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId));

            verify(traceDAO).search(anyInt(), any());
            verify(annotationQueueService).addItems(queueId, Set.of(traceId), AnnotationQueueItemSource.AUTOMATED);
            verify(annotationQueueService).addItems(secondQueueId, Set.of(traceId),
                    AnnotationQueueItemSource.AUTOMATED);
        }

        @Test
        void searchesOnlyTheAutomationsProject() {
            UUID ownTrace = UUID.randomUUID();
            UUID otherProjectTrace = UUID.randomUUID();
            UUID otherProjectId = UUID.randomUUID();
            when(traceDAO.getAllProjectIdsByTraceIdsBounded(any()))
                    .thenReturn(Mono.just(Map.of(ownTrace, Set.of(projectId), otherProjectTrace,
                            Set.of(otherProjectId))));
            when(automationService.findEnabledByProjects(WORKSPACE_ID, Set.of(projectId, otherProjectId),
                    AnnotationQueue.AnnotationScope.TRACE))
                    .thenReturn(List.of(automation(List.of(List.of(scoreFilter)))));
            givenLoggingSource(ownTrace, otherProjectTrace);
            when(traceDAO.search(anyInt(), any())).thenReturn(Flux.empty());

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(ownTrace, otherProjectTrace));

            var criteria = ArgumentCaptor.forClass(TraceSearchCriteria.class);
            verify(traceDAO).search(eq(1), criteria.capture());
            assertThat(criteria.getValue().traceIds()).containsExactly(ownTrace);
        }

        @Test
        void routesATraceIdReusedAcrossProjectsInEachProject() {
            UUID traceId = UUID.randomUUID();
            UUID otherProjectId = UUID.randomUUID();
            UUID otherQueueId = UUID.randomUUID();
            when(traceDAO.getAllProjectIdsByTraceIdsBounded(Set.of(traceId)))
                    .thenReturn(Mono.just(Map.of(traceId, Set.of(projectId, otherProjectId))));
            when(automationService.findEnabledByProjects(WORKSPACE_ID, Set.of(projectId, otherProjectId),
                    AnnotationQueue.AnnotationScope.TRACE))
                    .thenReturn(List.of(automation(List.of(List.of(scoreFilter))),
                            automation(List.of(List.of(scoreFilter))).toBuilder()
                                    .queueId(otherQueueId)
                                    .projectId(otherProjectId)
                                    .build()));
            givenLoggingSource(traceId);
            when(traceDAO.search(anyInt(), any())).thenReturn(Flux.just(trace(traceId)));
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(1L));

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId));

            var criteria = ArgumentCaptor.forClass(TraceSearchCriteria.class);
            verify(traceDAO, times(2)).search(eq(1), criteria.capture());
            assertThat(criteria.getAllValues())
                    .extracting(TraceSearchCriteria::projectId)
                    .containsExactlyInAnyOrder(projectId, otherProjectId);
            verify(annotationQueueService).addItems(queueId, Set.of(traceId), AnnotationQueueItemSource.AUTOMATED);
            verify(annotationQueueService).addItems(otherQueueId, Set.of(traceId),
                    AnnotationQueueItemSource.AUTOMATED);
        }

        /**
         * Threads are addressed by their own id on the threads page and by their model id in a queue, so the
         * search is narrowed by the former and its results mapped back to the latter.
         */
        @Test
        void narrowsTheThreadsSearchByThreadId() {
            UUID threadModelId = UUID.randomUUID();
            String threadId = RandomStringUtils.secure().nextAlphanumeric(12);
            when(traceThreadService.getThreadIdModelsByThreadModelIds(List.of(threadModelId)))
                    .thenReturn(Mono.just(List.of(TraceThreadIdModel.builder()
                            .id(threadModelId)
                            .projectId(projectId)
                            .threadId(threadId)
                            .build())));
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.THREAD, List.of(List.of()));
            when(traceThreadDAO.getLoggingSourceIds(Set.of(projectId), Set.of(threadModelId)))
                    .thenReturn(Mono.just(Set.of(threadModelId)));
            when(threadDAO.search(anyInt(), any())).thenReturn(Flux.just(TraceThread.builder()
                    .id(threadId)
                    .threadModelId(threadModelId)
                    .build()));
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(1L));

            process(AnnotationQueue.AnnotationScope.THREAD, Set.of(threadModelId));

            var criteria = ArgumentCaptor.forClass(TraceSearchCriteria.class);
            verify(threadDAO).search(eq(1), criteria.capture());
            assertThat(criteria.getValue().threadIds()).containsExactly(threadId);
            verify(annotationQueueService).addItems(queueId, Set.of(threadModelId),
                    AnnotationQueueItemSource.AUTOMATED);
            verifyNoInteractions(traceDAO);
        }

        /**
         * A thread's model id exists before its row is written, so a search can match a thread whose row has
         * no model id yet. It is left out, and writing the row routes it again.
         */
        @Test
        void routesTheOtherThreadsWhenAMatchHasNoModelIdYet() {
            UUID written = UUID.randomUUID();
            UUID notWritten = UUID.randomUUID();
            when(traceThreadService.getThreadIdModelsByThreadModelIds(any()))
                    .thenReturn(Mono.just(List.of(
                            TraceThreadIdModel.builder().id(written).projectId(projectId).threadId("written")
                                    .build(),
                            TraceThreadIdModel.builder().id(notWritten).projectId(projectId).threadId("not-written")
                                    .build())));
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.THREAD, List.of(List.of()));
            when(traceThreadDAO.getLoggingSourceIds(Set.of(projectId), Set.of(written, notWritten)))
                    .thenReturn(Mono.just(Set.of(written, notWritten)));
            when(threadDAO.search(anyInt(), any())).thenReturn(Flux.just(
                    TraceThread.builder().id("written").threadModelId(written).build(),
                    TraceThread.builder().id("not-written").build()));
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(1L));

            process(AnnotationQueue.AnnotationScope.THREAD, Set.of(written, notWritten));

            verify(annotationQueueService).addItems(queueId, Set.of(written), AnnotationQueueItemSource.AUTOMATED);
        }
    }

    /**
     * An automation routes production traffic to a human reviewer, so the same gate online scoring applies
     * before it samples — {@link Source#isLoggingSource} — applies here too. A Playground run or an
     * optimization is a developer trying things out, and nobody asked to review it.
     *
     * <p>Stricter in one respect: online scoring also admits {@link Source#EXPERIMENT}, because an
     * experiment's traces are what produce its metrics. Those are reviewed through the experiment
     * comparison view rather than a queue, so here they are filtered out with everything else.
     */
    @Nested
    @DisplayName("Source filtering")
    class SourceFilteringTests {

        @Test
        void skipsTraceNotLoggedBySdk() {
            UUID traceId = UUID.randomUUID();
            givenTraces(traceId);
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.TRACE, List.of(List.of(scoreFilter)));
            when(traceDAO.getLoggingSourceIds(Set.of(projectId), Set.of(traceId))).thenReturn(Mono.just(Set.of()));

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId));

            // Filtered before matching, not after: a skipped entity costs no search.
            verify(traceDAO, never()).search(anyInt(), any());
            verifyNoInteractions(annotationQueueService);
        }

        @Test
        void searchesOnlyTheSdkTraceOfAMixedBatch() {
            UUID sdkTraceId = UUID.randomUUID();
            UUID playgroundTraceId = UUID.randomUUID();
            givenTraces(sdkTraceId, playgroundTraceId);
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.TRACE, List.of(List.of(scoreFilter)));
            when(traceDAO.getLoggingSourceIds(eq(Set.of(projectId)), any()))
                    .thenReturn(Mono.just(Set.of(sdkTraceId)));
            when(traceDAO.search(anyInt(), any())).thenReturn(Flux.just(trace(sdkTraceId)));
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(1L));

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(sdkTraceId, playgroundTraceId));

            var criteria = ArgumentCaptor.forClass(TraceSearchCriteria.class);
            verify(traceDAO).search(eq(1), criteria.capture());
            assertThat(criteria.getValue().traceIds()).containsExactly(sdkTraceId);
        }

        /**
         * The source read costs a ClickHouse query, so it must not run for the majority of traffic that
         * belongs to no automated queue at all.
         */
        @Test
        void doesNotReadSourceWhenNoAutomationIsEnabled() {
            UUID traceId = UUID.randomUUID();
            givenTraces(traceId);
            when(automationService.findEnabledByProjects(anyString(), any(), any())).thenReturn(List.of());

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId));

            verify(traceDAO, never()).getLoggingSourceIds(any(), any());
            verifyNoInteractions(traceThreadDAO, annotationQueueService);
        }

        @Test
        void routesNothingForEntitiesThatNoLongerExist() {
            when(traceDAO.getAllProjectIdsByTraceIdsBounded(any())).thenReturn(Mono.just(Map.of()));

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(UUID.randomUUID()));

            verifyNoInteractions(automationService, annotationQueueService);
        }
    }

    /**
     * A failed queue write must leave the message pending rather than acknowledge it. Retrying is cheap and
     * cannot duplicate anything, because addItems excludes what a queue has already held - so swallowing the
     * failure would trade a free retry for a permanently unrouted trace.
     */
    @Nested
    @DisplayName("Queue write failures")
    class QueueWriteFailureTests {

        @Test
        void propagatesTheFailureSoTheMessageIsRetried() {
            UUID traceId = UUID.randomUUID();
            givenMatchingTrace(traceId);
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.TRACE, List.of(List.of(scoreFilter)));
            var failure = new IllegalStateException("clickhouse down");
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.error(failure));

            assertThatThrownBy(() -> process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId)))
                    .isSameAs(failure);
        }

        /**
         * A failed search leaves the message pending like a failed write, without keeping the other automations'
         * matches out of their queues.
         */
        @Test
        void routesTheOtherGroupsWhenOneSearchFails() {
            UUID traceId = UUID.randomUUID();
            UUID secondQueueId = UUID.randomUUID();
            Filter otherFilter = scoreFilter.build("0.9");
            givenTraces(traceId);
            givenLoggingSource(traceId);
            when(automationService.findEnabledByProjects(WORKSPACE_ID, Set.of(projectId),
                    AnnotationQueue.AnnotationScope.TRACE))
                    .thenReturn(List.of(automation(List.of(List.of(scoreFilter))),
                            automation(List.of(List.of(otherFilter))).toBuilder().queueId(secondQueueId).build()));
            var failure = new IllegalStateException("clickhouse down");
            when(traceDAO.search(anyInt(), any())).thenAnswer(invocation -> {
                TraceSearchCriteria criteria = invocation.getArgument(1);
                return criteria.filters().contains(scoreFilter) ? Flux.error(failure) : Flux.just(trace(traceId));
            });
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(1L));

            assertThatThrownBy(() -> process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId)))
                    .isSameAs(failure);

            verify(annotationQueueService).addItems(secondQueueId, Set.of(traceId),
                    AnnotationQueueItemSource.AUTOMATED);
            verify(annotationQueueService, never()).addItems(eq(queueId), any(), any());
        }

        @Test
        void attemptsEveryQueueBeforeFailing() {
            UUID traceId = UUID.randomUUID();
            UUID secondQueueId = UUID.randomUUID();
            givenMatchingTrace(traceId);
            givenTwoQueues(secondQueueId);
            when(annotationQueueService.addItems(eq(queueId), any(), any()))
                    .thenReturn(Mono.error(new IllegalStateException("clickhouse down")));
            when(annotationQueueService.addItems(eq(secondQueueId), any(), any())).thenReturn(Mono.just(1L));

            assertThatThrownBy(() -> process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId)))
                    .isInstanceOf(IllegalStateException.class);

            // The healthy queue still received its items - one bad queue cannot starve the rest.
            verify(annotationQueueService).addItems(eq(secondQueueId), any(), any());
        }

        @Test
        void reportsOneFailureWithTheOthersAttached() {
            UUID traceId = UUID.randomUUID();
            UUID secondQueueId = UUID.randomUUID();
            givenMatchingTrace(traceId);
            givenTwoQueues(secondQueueId);
            var first = new IllegalStateException("clickhouse down");
            var second = new IllegalStateException("clickhouse still down");
            when(annotationQueueService.addItems(eq(queueId), any(), any())).thenReturn(Mono.error(first));
            when(annotationQueueService.addItems(eq(secondQueueId), any(), any())).thenReturn(Mono.error(second));

            assertThatThrownBy(() -> process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId)))
                    .isInstanceOf(IllegalStateException.class)
                    // contains, not containsExactly: block() appends its own "#block terminated" marker.
                    .satisfies(error -> assertThat(error.getSuppressed())
                            .containsAnyOf(first, second));
        }

        private void givenTwoQueues(UUID secondQueueId) {
            when(automationService.findEnabledByProjects(WORKSPACE_ID, Set.of(projectId),
                    AnnotationQueue.AnnotationScope.TRACE))
                    .thenReturn(List.of(automation(List.of(List.of(scoreFilter))),
                            automation(List.of(List.of(scoreFilter))).toBuilder().queueId(secondQueueId).build()));
        }
    }

    private void process(AnnotationQueue.AnnotationScope scope, Set<UUID> entityIds) {
        subscriber.processEvent(AnnotationQueueRoutingMessage.builder()
                .workspaceId(WORKSPACE_ID)
                .scope(scope)
                .entityIds(entityIds)
                .build())
                .block();
    }

    private void givenTraces(UUID... traceIds) {
        when(traceDAO.getAllProjectIdsByTraceIdsBounded(any())).thenReturn(Mono.just(Arrays.stream(traceIds)
                .collect(Collectors.toMap(Function.identity(), __ -> Set.of(projectId)))));
    }

    private void givenMatchingTrace(UUID traceId) {
        givenTraces(traceId);
        givenLoggingSource(traceId);
        when(traceDAO.search(anyInt(), any())).thenReturn(Flux.just(trace(traceId)));
    }

    private void givenEnabledAutomation(AnnotationQueue.AnnotationScope scope,
            List<List<Filter>> filterGroups) {
        when(automationService.findEnabledByProjects(WORKSPACE_ID, Set.of(projectId), scope))
                .thenReturn(List.of(automation(filterGroups)));
    }

    private QueueAutomation automation(List<List<Filter>> filterGroups) {
        return QueueAutomation.builder()
                .queueId(queueId)
                .projectId(projectId)
                .filterGroups(filterGroups)
                .build();
    }

    private void givenLoggingSource(UUID... entityIds) {
        when(traceDAO.getLoggingSourceIds(any(), any())).thenReturn(Mono.just(Set.of(entityIds)));
    }

    private static Trace trace(UUID id) {
        return Trace.builder().id(id).build();
    }
}
