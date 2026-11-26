package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.events.CommentsCreated;
import com.comet.opik.api.events.CommentsDeleted;
import com.comet.opik.api.events.FeedbackScoresCreated;
import com.comet.opik.api.events.FeedbackScoresDeleted;
import com.comet.opik.api.events.GuardrailsCreated;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.api.events.SpansDeleted;
import com.comet.opik.api.events.SpansUpdated;
import com.comet.opik.api.events.TraceThreadsCreated;
import com.comet.opik.api.events.TraceThreadsUpdated;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.AnnotationQueueAutomationService;
import com.comet.opik.domain.AnnotationQueueRoutingBufferService;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanDAO;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.domain.threads.TraceThreadModel;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.FeatureFlags;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.AnnotationQueue.AnnotationScope;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers which events reach the buffer as which entities, and the guards in front of them: the listener is
 * the feature's only volume control, so a guard that stops working means every ingestion event in the
 * deployment reaches the buffer.
 *
 * <p>Unit rather than black box because the listener's entry point is the Guava event bus, and the router
 * rule it reads has no REST surface on this PR — there is nothing to drive it through from outside. What
 * can be covered end to end is, in {@link AnnotationQueueRoutingIntegrationTest}, which runs this same
 * listener against a real buffer, a real flush and a real Redis stream.
 *
 * <p>The listener subscribes and returns, so anything past the guards happens on another thread. Every
 * assertion here is timed for that reason: one that should see work waits for it, and one that should see
 * none waits before concluding. An immediate check would pass whether the guard held or simply had not
 * been overtaken yet - verified by deleting each guard and watching the matching test go red.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnnotationQueueRoutingListenerTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    @Mock
    private AnnotationQueueAutomationService automationService;

    @Mock
    private AnnotationQueueRoutingBufferService bufferService;

    @Mock
    private FeatureFlags featureFlags;

    @Mock
    private SpanDAO spanDAO;

    @Mock
    private TraceDAO traceDAO;

    @Mock
    private TraceThreadService traceThreadService;

    private AnnotationQueueRoutingListener listener;

    private String workspaceId;
    private String userName;

    @BeforeEach
    void setUp() {
        workspaceId = randomString();
        userName = randomString();
        when(featureFlags.isAnnotationQueueAutomationEnabled()).thenReturn(true);
        when(bufferService.add(anyString(), any(), any())).thenReturn(Mono.empty());
        listener = new AnnotationQueueRoutingListener(automationService, bufferService, featureFlags, spanDAO,
                traceDAO, traceThreadService);
    }

    private static String randomString() {
        return RandomStringUtils.secure().nextAlphanumeric(20);
    }

    private FeedbackScoresCreated event(EntityType entityType, UUID projectId, Set<UUID> entityIds) {
        return new FeedbackScoresCreated(entityIds, entityType, workspaceId, userName, projectId);
    }

    @Nested
    @DisplayName("Published")
    class Published {

        /**
         * The scope mapping and the two guard flavours in one table. The null project id is the batch score
         * path, which cannot name a project because one batch may span several — the listener passes the
         * null straight through, and the service widens the question to the workspace.
         */
        static Stream<Arguments> published() {
            return Stream.of(
                    Arguments.of(EntityType.TRACE, AnnotationScope.TRACE, true),
                    Arguments.of(EntityType.TRACE, AnnotationScope.TRACE, false),
                    Arguments.of(EntityType.THREAD, AnnotationScope.THREAD, true),
                    Arguments.of(EntityType.THREAD, AnnotationScope.THREAD, false));
        }

        @ParameterizedTest
        @MethodSource("published")
        void buffersWhateverTheGuardAdmits(EntityType entityType, AnnotationScope scope, boolean hasProjectId) {
            UUID projectId = hasProjectId ? ID_GENERATOR.generateId() : null;
            Set<UUID> entityIds = Set.of(ID_GENERATOR.generateId(), ID_GENERATOR.generateId());
            when(automationService.hasEnabledAutomation(workspaceId, projectId, scope)).thenReturn(true);

            listener.onFeedbackScoresCreated(event(entityType, projectId, entityIds));

            verify(bufferService, timeout(2_000)).add(workspaceId, scope, entityIds);
        }

        @Test
        void routesASpanScoreToItsTrace() {
            UUID projectId = ID_GENERATOR.generateId();
            Set<UUID> spanIds = Set.of(ID_GENERATOR.generateId());
            Set<UUID> traceIds = Set.of(ID_GENERATOR.generateId());
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.TRACE))
                    .thenReturn(true);
            when(spanDAO.getTraceIdsBySpanIds(spanIds)).thenReturn(Mono.just(traceIds));

            listener.onFeedbackScoresCreated(event(EntityType.SPAN, projectId, spanIds));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.TRACE, traceIds);
        }

        @Test
        void routesCreatedTracesAndTheirThreads() {
            UUID projectId = ID_GENERATOR.generateId();
            UUID threadModelId = ID_GENERATOR.generateId();
            String threadId = randomString();
            var inThread = Trace.builder().id(ID_GENERATOR.generateId()).projectId(projectId).threadId(threadId)
                    .build();
            var standalone = Trace.builder().id(ID_GENERATOR.generateId()).projectId(projectId).build();
            when(automationService.hasEnabledAutomation(any(), any(), any())).thenReturn(true);
            when(traceThreadService.findThreadModelIds(projectId, Set.of(threadId)))
                    .thenReturn(Mono.just(Map.of(threadId, threadModelId)));

            listener.onTracesCreated(new TracesCreated(List.of(inThread, standalone), workspaceId, userName));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.TRACE,
                    Set.of(inThread.id(), standalone.id()));
            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.THREAD, Set.of(threadModelId));
        }

        @Test
        void routesUpdatedTraces() {
            UUID projectId = ID_GENERATOR.generateId();
            Set<UUID> traceIds = Set.of(ID_GENERATOR.generateId());
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.TRACE))
                    .thenReturn(true);

            listener.onTracesUpdated(new TracesUpdated(Set.of(projectId), traceIds, workspaceId, userName,
                    TraceUpdate.builder().build()));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.TRACE, traceIds);
        }

        @Test
        void routesTheTracesOfCreatedSpans() {
            UUID projectId = ID_GENERATOR.generateId();
            UUID traceId = ID_GENERATOR.generateId();
            var spans = List.of(
                    Span.builder().id(ID_GENERATOR.generateId()).projectId(projectId).traceId(traceId).build(),
                    Span.builder().id(ID_GENERATOR.generateId()).projectId(projectId).traceId(traceId).build());
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.TRACE))
                    .thenReturn(true);

            listener.onSpansCreated(new SpansCreated(spans, workspaceId, userName));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.TRACE, Set.of(traceId));
        }

        @Test
        void routesTheTracesOfUpdatedSpans() {
            Set<UUID> traceIds = Set.of(ID_GENERATOR.generateId());
            when(automationService.hasEnabledAutomation(workspaceId, null, AnnotationScope.TRACE))
                    .thenReturn(true);

            listener.onSpansUpdated(new SpansUpdated(traceIds, workspaceId, userName));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.TRACE, traceIds);
        }

        @Test
        void routesTracesWithNewGuardrails() {
            UUID projectId = ID_GENERATOR.generateId();
            Set<UUID> traceIds = Set.of(ID_GENERATOR.generateId());
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.TRACE))
                    .thenReturn(true);

            listener.onGuardrailsCreated(new GuardrailsCreated(traceIds, workspaceId, userName, projectId));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.TRACE, traceIds);
        }

        @Test
        void routesCommentedThreads() {
            UUID projectId = ID_GENERATOR.generateId();
            Set<UUID> threadModelIds = Set.of(ID_GENERATOR.generateId());
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.THREAD))
                    .thenReturn(true);

            listener.onCommentsCreated(new CommentsCreated(threadModelIds, EntityType.THREAD, workspaceId, userName,
                    projectId));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.THREAD, threadModelIds);
        }

        @Test
        void routesTracesWhoseScoresWereDeleted() {
            UUID projectId = ID_GENERATOR.generateId();
            Set<UUID> traceIds = Set.of(ID_GENERATOR.generateId());
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.TRACE))
                    .thenReturn(true);

            listener.onFeedbackScoresDeleted(new FeedbackScoresDeleted(traceIds, EntityType.TRACE, workspaceId,
                    userName, projectId));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.TRACE, traceIds);
        }

        @Test
        void routesThreadsWhoseCommentsWereDeleted() {
            UUID projectId = ID_GENERATOR.generateId();
            Set<UUID> threadModelIds = Set.of(ID_GENERATOR.generateId());
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.THREAD))
                    .thenReturn(true);

            listener.onCommentsDeleted(new CommentsDeleted(threadModelIds, EntityType.THREAD, workspaceId, userName,
                    projectId));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.THREAD, threadModelIds);
        }

        @Test
        void routesTheTracesOfDeletedSpans() {
            UUID projectId = ID_GENERATOR.generateId();
            Set<UUID> traceIds = Set.of(ID_GENERATOR.generateId());
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.TRACE))
                    .thenReturn(true);

            listener.onSpansDeleted(new SpansDeleted(Set.of(ID_GENERATOR.generateId()), traceIds, workspaceId,
                    userName, projectId));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.TRACE, traceIds);
        }

        @Test
        void routesCreatedThreads() {
            UUID projectId = ID_GENERATOR.generateId();
            UUID threadModelId = ID_GENERATOR.generateId();
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.THREAD))
                    .thenReturn(true);

            listener.onTraceThreadsCreated(new TraceThreadsCreated(
                    List.of(TraceThreadModel.builder().id(threadModelId).build()), projectId, workspaceId,
                    userName));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.THREAD, Set.of(threadModelId));
        }

        /**
         * A trace's output and end time are its thread's last message and end, and they often arrive with the
         * trace's update rather than its creation.
         */
        @Test
        void routesTheThreadsOfUpdatedTraces() {
            UUID projectId = ID_GENERATOR.generateId();
            Set<UUID> traceIds = Set.of(ID_GENERATOR.generateId(), ID_GENERATOR.generateId());
            String threadId = randomString();
            UUID threadModelId = ID_GENERATOR.generateId();
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.THREAD))
                    .thenReturn(true);
            when(traceDAO.getThreadIdsByTraceIds(traceIds)).thenReturn(Mono.just(Map.of(projectId, Set.of(threadId))));
            when(traceThreadService.findThreadModelIds(projectId, Set.of(threadId)))
                    .thenReturn(Mono.just(Map.of(threadId, threadModelId)));

            listener.onTracesUpdated(new TracesUpdated(Set.of(projectId), traceIds, workspaceId, userName,
                    TraceUpdate.builder().build()));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.THREAD, Set.of(threadModelId));
        }

        /**
         * A thread's own tags and its closing reach no trace, so they come as their own event.
         */
        @Test
        void routesUpdatedThreads() {
            UUID projectId = ID_GENERATOR.generateId();
            Set<UUID> threadModelIds = Set.of(ID_GENERATOR.generateId(), ID_GENERATOR.generateId());
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.THREAD))
                    .thenReturn(true);

            listener.onTraceThreadsUpdated(new TraceThreadsUpdated(projectId, threadModelIds, workspaceId, userName));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.THREAD, threadModelIds);
        }

        /**
         * A batch update names threads without their project, so the guard falls back to the workspace.
         */
        @Test
        void routesBatchUpdatedThreadsOnTheWorkspaceGuard() {
            Set<UUID> threadModelIds = Set.of(ID_GENERATOR.generateId());
            when(automationService.hasEnabledAutomation(workspaceId, null, AnnotationScope.THREAD))
                    .thenReturn(true);

            listener.onTraceThreadsUpdated(new TraceThreadsUpdated(null, threadModelIds, workspaceId, userName));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.THREAD, threadModelIds);
        }
    }

    @Nested
    @DisplayName("Skipped")
    class Skipped {

        @Test
        void publishesNothingWhenRoutingIsDisabled() {
            // The guard below is a database round trip on the busiest event in the system, so a disabled
            // feature must not reach it either.
            when(featureFlags.isAnnotationQueueAutomationEnabled()).thenReturn(false);

            listener.onFeedbackScoresCreated(event(EntityType.TRACE, ID_GENERATOR.generateId(),
                    Set.of(ID_GENERATOR.generateId())));

            assertNothingRouted();
        }

        /**
         * Resolving a span's trace is a ClickHouse read, so it must wait for the guard like the buffer does.
         */
        @Test
        void resolvesNoSpanTracesWhenNoAutomationIsEnabled() {
            UUID projectId = ID_GENERATOR.generateId();
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.TRACE))
                    .thenReturn(false);

            listener.onFeedbackScoresCreated(event(EntityType.SPAN, projectId, Set.of(ID_GENERATOR.generateId())));

            verify(automationService, timeout(2_000)).hasEnabledAutomation(workspaceId, projectId,
                    AnnotationScope.TRACE);
            verify(spanDAO, after(400).never()).getTraceIdsBySpanIds(any());
            verify(bufferService, never()).add(anyString(), any(), any());
        }

        /**
         * Resolving an updated trace's thread is a ClickHouse read, so it waits for the thread guard.
         */
        @Test
        void resolvesNoThreadsOfUpdatedTracesWhenNoThreadAutomationIsEnabled() {
            UUID projectId = ID_GENERATOR.generateId();
            Set<UUID> traceIds = Set.of(ID_GENERATOR.generateId());
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.TRACE))
                    .thenReturn(true);
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.THREAD))
                    .thenReturn(false);

            listener.onTracesUpdated(new TracesUpdated(Set.of(projectId), traceIds, workspaceId, userName,
                    TraceUpdate.builder().build()));

            verify(bufferService, timeout(2_000)).add(workspaceId, AnnotationScope.TRACE, traceIds);
            verify(automationService, timeout(2_000)).hasEnabledAutomation(workspaceId, projectId,
                    AnnotationScope.THREAD);
            verify(traceDAO, after(400).never()).getThreadIdsByTraceIds(any());
            verify(bufferService, never()).add(anyString(), eq(AnnotationScope.THREAD), any());
        }

        /**
         * The comments filter reads a trace's or thread's own comments, so a span comment cannot change a match.
         */
        @Test
        void publishesNothingForSpanComments() {
            listener.onCommentsCreated(new CommentsCreated(Set.of(ID_GENERATOR.generateId()), EntityType.SPAN,
                    workspaceId, userName, ID_GENERATOR.generateId()));
            listener.onCommentsDeleted(new CommentsDeleted(Set.of(ID_GENERATOR.generateId()), EntityType.SPAN,
                    workspaceId, userName, ID_GENERATOR.generateId()));

            assertNothingRouted();
        }

        @Test
        void publishesNothingWhenTheEventCarriesNoEntities() {
            listener.onFeedbackScoresCreated(
                    event(EntityType.TRACE, ID_GENERATOR.generateId(), Set.of()));

            assertNothingRouted();
        }

        @Test
        void publishesNothingWhenNoAutomationIsEnabled() {
            UUID projectId = ID_GENERATOR.generateId();
            when(automationService.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.TRACE))
                    .thenReturn(false);

            listener.onFeedbackScoresCreated(
                    event(EntityType.TRACE, projectId, Set.of(ID_GENERATOR.generateId())));

            // Wait for the lookup itself, so "never published" is a real assertion rather than a race won.
            verify(automationService, timeout(2_000)).hasEnabledAutomation(workspaceId, projectId,
                    AnnotationScope.TRACE);
            verify(bufferService, never()).add(anyString(), any(), any());
        }

        /**
         * Neither the lookup nor the buffer is reached. Timed rather than immediate: a deleted guard
         * makes the lookup asynchronous, and an immediate assertion would simply run first and pass.
         */
        private void assertNothingRouted() {
            verify(automationService, after(400).never()).hasEnabledAutomation(anyString(), any(), any());
            verify(bufferService, never()).add(anyString(), any(), any());
        }
    }
}
