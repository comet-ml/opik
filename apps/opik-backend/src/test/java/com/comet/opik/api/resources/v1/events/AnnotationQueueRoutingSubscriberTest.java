package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueItemSource;
import com.comet.opik.api.Source;
import com.comet.opik.domain.AnnotationQueueAutomationService;
import com.comet.opik.domain.AnnotationQueueAutomationService.QueueAutomation;
import com.comet.opik.domain.AnnotationQueueConditionEvaluator;
import com.comet.opik.domain.AnnotationQueueRoutingMessage;
import com.comet.opik.domain.AnnotationQueueService;
import com.comet.opik.domain.EntityFeedbackScores;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.FeedbackScoreDAO;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.domain.threads.TraceThreadDAO;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers which scored entities the routing consumer is willing to route, which is what decides what a
 * reviewer ends up being asked to look at.
 */
@ExtendWith(MockitoExtension.class)
class AnnotationQueueRoutingSubscriberTest {

    private static final String WORKSPACE_ID = "workspace-1";
    private static final String USER_NAME = "user-1";
    private static final String SCORE_NAME = "relevance";

    @Mock
    private RedissonReactiveClient redisson;
    @Mock
    private AnnotationQueueAutomationService automationService;
    @Mock
    private AnnotationQueueConditionEvaluator evaluator;
    @Mock
    private AnnotationQueueService annotationQueueService;
    @Mock
    private FeedbackScoreDAO feedbackScoreDAO;
    @Mock
    private TraceDAO traceDAO;
    @Mock
    private TraceThreadDAO traceThreadDAO;

    private UUID projectId;
    private UUID queueId;
    private AnnotationQueueRoutingSubscriber subscriber;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        queueId = UUID.randomUUID();
        subscriber = new AnnotationQueueRoutingSubscriber(new AnnotationQueueRoutingConfig(), redisson,
                automationService, evaluator, annotationQueueService, feedbackScoreDAO, traceDAO, traceThreadDAO);
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
        void routesTraceLoggedBySdk() {
            UUID traceId = UUID.randomUUID();
            givenScored(EntityType.TRACE, traceId);
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.TRACE);
            givenConditionsMatch();
            when(traceDAO.getLoggingSourceIds(Set.of(projectId), Set.of(traceId)))
                    .thenReturn(Mono.just(Set.of(traceId)));
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(1L));

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId));

            verify(annotationQueueService).addItems(queueId, Set.of(traceId), AnnotationQueueItemSource.AUTOMATED);
        }

        @Test
        void skipsTraceNotLoggedBySdk() {
            UUID traceId = UUID.randomUUID();
            givenScored(EntityType.TRACE, traceId);
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.TRACE);
            // What a playground, experiment, optimization or evaluator trace looks like to the filter: the
            // query matches nothing, so the entity never reaches the evaluator.
            when(traceDAO.getLoggingSourceIds(Set.of(projectId), Set.of(traceId))).thenReturn(Mono.just(Set.of()));

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId));

            // Filtered before evaluation, not after: a skipped entity costs no condition evaluation.
            verifyNoInteractions(evaluator, annotationQueueService);
        }

        @Test
        void routesOnlyTheSdkTraceOfAMixedBatch() {
            UUID sdkTraceId = UUID.randomUUID();
            UUID playgroundTraceId = UUID.randomUUID();
            givenScored(EntityType.TRACE, sdkTraceId, playgroundTraceId);
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.TRACE);
            givenConditionsMatch();
            when(traceDAO.getLoggingSourceIds(eq(Set.of(projectId)), any()))
                    .thenReturn(Mono.just(Set.of(sdkTraceId)));
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(1L));

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(sdkTraceId, playgroundTraceId));

            verify(annotationQueueService).addItems(queueId, Set.of(sdkTraceId),
                    AnnotationQueueItemSource.AUTOMATED);
        }

        @Test
        void readsThreadSourceForThreadScope() {
            UUID threadModelId = UUID.randomUUID();
            givenScored(EntityType.THREAD, threadModelId);
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.THREAD);
            givenConditionsMatch();
            when(traceThreadDAO.getLoggingSourceIds(Set.of(projectId), Set.of(threadModelId)))
                    .thenReturn(Mono.just(Set.of(threadModelId)));
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(1L));

            process(AnnotationQueue.AnnotationScope.THREAD, Set.of(threadModelId));

            verifyNoInteractions(traceDAO);
            verify(annotationQueueService).addItems(queueId, Set.of(threadModelId),
                    AnnotationQueueItemSource.AUTOMATED);
        }

        /**
         * The filter costs a ClickHouse read, so it must not run for the majority of scored traffic that
         * belongs to no automated queue at all.
         */
        @Test
        void doesNotReadSourceWhenNoAutomationIsEnabled() {
            UUID traceId = UUID.randomUUID();
            givenScored(EntityType.TRACE, traceId);
            when(automationService.findEnabledByProjects(anyString(), any(), any())).thenReturn(List.of());

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId));

            verifyNoInteractions(traceDAO, traceThreadDAO, annotationQueueService);
        }
    }

    /**
     * A score written to an entity that already has others is the normal case - a judge scoring what a human
     * scored, or the reverse - and it is the case a bare "no scores at all" check cannot see. If the read
     * misses the new score, the conditions do not match, the message is acknowledged, and with no backfill
     * the entity is never routed.
     */
    @Nested
    @DisplayName("Stale read handling")
    class StaleReadTests {

        @Test
        void reReadsWhenTheScoreTheEventNamedIsNotVisibleYet() {
            UUID traceId = UUID.randomUUID();
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.TRACE);
            givenConditionsMatch();
            givenLoggingSource(traceId);
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(1L));

            // First read has the old score only; the second sees the one the event named.
            when(feedbackScoreDAO.getEffectiveScores(eq(EntityType.TRACE), any()))
                    .thenReturn(Mono.just(scoresOf(traceId, Map.of("safety", BigDecimal.valueOf(0.8)))))
                    .thenReturn(Mono.just(scoresOf(traceId,
                            Map.of("safety", BigDecimal.valueOf(0.8), SCORE_NAME, BigDecimal.valueOf(0.2)))));

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId), Map.of(traceId, Set.of(SCORE_NAME)));

            verify(feedbackScoreDAO, times(2)).getEffectiveScores(eq(EntityType.TRACE), any());
            verify(annotationQueueService).addItems(queueId, Set.of(traceId), AnnotationQueueItemSource.AUTOMATED);
        }

        @Test
        void doesNotReReadWhenEveryNamedScoreIsAlreadyVisible() {
            UUID traceId = UUID.randomUUID();
            givenScored(EntityType.TRACE, traceId);
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.TRACE);
            givenConditionsMatch();
            givenLoggingSource(traceId);
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(1L));

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId), Map.of(traceId, Set.of(SCORE_NAME)));

            verify(feedbackScoreDAO, times(1)).getEffectiveScores(eq(EntityType.TRACE), any());
        }

        /** Names are best-effort: an entity the message says nothing about is judged on emptiness alone. */
        @Test
        void treatsAnAbsentNameSetAsNoInformationRatherThanAsNoScores() {
            UUID traceId = UUID.randomUUID();
            givenScored(EntityType.TRACE, traceId);
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.TRACE);
            givenConditionsMatch();
            givenLoggingSource(traceId);
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.just(1L));

            process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId), Map.of());

            verify(feedbackScoreDAO, times(1)).getEffectiveScores(eq(EntityType.TRACE), any());
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
            givenScored(EntityType.TRACE, traceId);
            givenEnabledAutomation(AnnotationQueue.AnnotationScope.TRACE);
            givenConditionsMatch();
            givenLoggingSource(traceId);
            var failure = new IllegalStateException("clickhouse down");
            when(annotationQueueService.addItems(any(), any(), any())).thenReturn(Mono.error(failure));

            assertThatThrownBy(() -> process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId)))
                    .isSameAs(failure);
        }

        @Test
        void attemptsEveryQueueBeforeFailing() {
            UUID traceId = UUID.randomUUID();
            UUID secondQueueId = UUID.randomUUID();
            givenScored(EntityType.TRACE, traceId);
            when(automationService.findEnabledByProjects(WORKSPACE_ID, Set.of(projectId),
                    AnnotationQueue.AnnotationScope.TRACE))
                    .thenReturn(List.of(new QueueAutomation(queueId, projectId, null),
                            new QueueAutomation(secondQueueId, projectId, null)));
            givenConditionsMatch();
            givenLoggingSource(traceId);
            when(annotationQueueService.addItems(eq(queueId), any(), any()))
                    .thenReturn(Mono.error(new IllegalStateException("clickhouse down")));
            when(annotationQueueService.addItems(eq(secondQueueId), any(), any())).thenReturn(Mono.just(1L));

            assertThatThrownBy(() -> process(AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId)))
                    .isInstanceOf(IllegalStateException.class);

            // The healthy queue still received its items - one bad queue cannot starve the rest.
            verify(annotationQueueService).addItems(eq(secondQueueId), any(), any());
        }
    }

    private void process(AnnotationQueue.AnnotationScope scope, Set<UUID> entityIds) {
        process(scope, entityIds, Map.of());
    }

    private void process(AnnotationQueue.AnnotationScope scope, Set<UUID> entityIds,
            Map<UUID, Set<String>> scoreNamesByEntity) {
        subscriber.processEvent(AnnotationQueueRoutingMessage.builder()
                .workspaceId(WORKSPACE_ID)
                .userName(USER_NAME)
                .scope(scope)
                .entityIds(entityIds)
                .scoreNamesByEntity(scoreNamesByEntity)
                .build())
                .block();
    }

    private void givenScored(EntityType entityType, UUID... entityIds) {
        Map<UUID, EntityFeedbackScores> scores = Arrays.stream(entityIds)
                .collect(Collectors.toMap(id -> id, id -> EntityFeedbackScores.builder()
                        .entityId(id)
                        .projectId(projectId)
                        .scores(Map.of(SCORE_NAME, BigDecimal.valueOf(0.2)))
                        .build()));

        when(feedbackScoreDAO.getEffectiveScores(eq(entityType), any())).thenReturn(Mono.just(scores));
    }

    private void givenEnabledAutomation(AnnotationQueue.AnnotationScope scope) {
        when(automationService.findEnabledByProjects(WORKSPACE_ID, Set.of(projectId), scope))
                .thenReturn(List.of(new QueueAutomation(queueId, projectId, null)));
    }

    private void givenConditionsMatch() {
        when(evaluator.matches(any(), any())).thenReturn(true);
    }

    private void givenLoggingSource(UUID... entityIds) {
        when(traceDAO.getLoggingSourceIds(any(), any())).thenReturn(Mono.just(Set.of(entityIds)));
    }

    private Map<UUID, EntityFeedbackScores> scoresOf(UUID entityId, Map<String, BigDecimal> scores) {
        return Map.of(entityId, EntityFeedbackScores.builder()
                .entityId(entityId)
                .projectId(projectId)
                .scores(scores)
                .build());
    }
}
