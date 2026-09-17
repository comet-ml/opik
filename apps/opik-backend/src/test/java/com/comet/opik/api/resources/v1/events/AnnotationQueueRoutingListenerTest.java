package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.events.FeedbackScoresCreated;
import com.comet.opik.domain.AnnotationQueueAutomationService;
import com.comet.opik.domain.AnnotationQueueRoutingBufferService;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the guards, and above all what they let through: the listener is the feature's only volume
 * control, so a guard that stops working means every score event in the deployment reaches the buffer.
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

    private static final String WORKSPACE_ID = "workspace-1";
    private static final String USER_NAME = "user-1";

    @Mock
    private AnnotationQueueAutomationService automationService;

    @Mock
    private AnnotationQueueRoutingBufferService bufferService;

    @Mock
    private AnnotationQueueRoutingConfig config;

    private AnnotationQueueRoutingListener listener;

    @BeforeEach
    void setUp() {
        when(config.isEnabled()).thenReturn(true);
        when(bufferService.record(anyString(), anyString(), any(), any(), any())).thenReturn(Mono.empty());
        listener = new AnnotationQueueRoutingListener(automationService, bufferService, config);
    }

    private FeedbackScoresCreated event(EntityType entityType, UUID projectId, Set<UUID> entityIds,
            Set<String> scoreNames) {
        return new FeedbackScoresCreated(entityIds, entityType, WORKSPACE_ID, USER_NAME, projectId, scoreNames);
    }

    @Nested
    @DisplayName("Recorded")
    class Recorded {

        @Test
        void recordsThroughTheProjectGuardWhenTheEventNamesAProject() {
            UUID projectId = ID_GENERATOR.generateId();
            UUID traceId = ID_GENERATOR.generateId();
            when(automationService.hasEnabledAutomation(WORKSPACE_ID, projectId, AnnotationQueue.AnnotationScope.TRACE))
                    .thenReturn(true);

            listener.onFeedbackScoresCreated(
                    event(EntityType.TRACE, projectId, Set.of(traceId), Set.of("relevance")));

            verify(bufferService, timeout(2_000)).record(WORKSPACE_ID, USER_NAME,
                    AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId), Set.of("relevance"));
        }

        @Test
        void recordsThroughTheWorkspaceGuardWhenTheEventNamesNoProject() {
            // The trace batch path cannot name a project, because one batch may span several.
            UUID traceId = ID_GENERATOR.generateId();
            when(automationService.hasEnabledAutomation(WORKSPACE_ID, AnnotationQueue.AnnotationScope.TRACE))
                    .thenReturn(true);

            listener.onFeedbackScoresCreated(event(EntityType.TRACE, null, Set.of(traceId), Set.of()));

            verify(bufferService, timeout(2_000)).record(WORKSPACE_ID, USER_NAME,
                    AnnotationQueue.AnnotationScope.TRACE, Set.of(traceId), Set.of());
            verify(automationService, never()).hasEnabledAutomation(anyString(), any(UUID.class), any());
        }

        @Test
        void recordsThreadsUnderTheThreadScope() {
            UUID projectId = ID_GENERATOR.generateId();
            UUID threadId = ID_GENERATOR.generateId();
            when(automationService.hasEnabledAutomation(WORKSPACE_ID, projectId,
                    AnnotationQueue.AnnotationScope.THREAD)).thenReturn(true);

            listener.onFeedbackScoresCreated(event(EntityType.THREAD, projectId, Set.of(threadId), Set.of()));

            verify(bufferService, timeout(2_000)).record(eq(WORKSPACE_ID), eq(USER_NAME),
                    eq(AnnotationQueue.AnnotationScope.THREAD), any(), any());
        }
    }

    @Nested
    @DisplayName("Skipped")
    class Skipped {

        @Test
        void recordsNothingWhenRoutingIsDisabled() {
            // The guard below is a database round trip on the busiest event in the system, so a disabled
            // feature must not reach it either.
            when(config.isEnabled()).thenReturn(false);

            listener.onFeedbackScoresCreated(event(EntityType.TRACE, ID_GENERATOR.generateId(),
                    Set.of(ID_GENERATOR.generateId()), Set.of()));

            assertNothingRouted();
        }

        @Test
        void recordsNothingForSpans() {
            listener.onFeedbackScoresCreated(event(EntityType.SPAN, ID_GENERATOR.generateId(),
                    Set.of(ID_GENERATOR.generateId()), Set.of()));

            assertNothingRouted();
        }

        @Test
        void recordsNothingWhenTheEventCarriesNoEntities() {
            listener.onFeedbackScoresCreated(
                    event(EntityType.TRACE, ID_GENERATOR.generateId(), Set.of(), Set.of()));

            assertNothingRouted();
        }

        @Test
        void recordsNothingWhenNoAutomationIsEnabled() {
            UUID projectId = ID_GENERATOR.generateId();
            when(automationService.hasEnabledAutomation(WORKSPACE_ID, projectId, AnnotationQueue.AnnotationScope.TRACE))
                    .thenReturn(false);

            listener.onFeedbackScoresCreated(
                    event(EntityType.TRACE, projectId, Set.of(ID_GENERATOR.generateId()), Set.of()));

            // Wait for the lookup itself, so "never recorded" is a real assertion rather than a race won.
            verify(automationService, timeout(2_000)).hasEnabledAutomation(WORKSPACE_ID, projectId,
                    AnnotationQueue.AnnotationScope.TRACE);
            verify(bufferService, never()).record(anyString(), anyString(), any(), any(), any());
        }

        /**
         * Neither the lookup nor the buffer is reached. Timed rather than immediate: a deleted guard makes
         * the lookup asynchronous, and an immediate assertion would simply run first and pass.
         */
        private void assertNothingRouted() {
            verify(automationService, after(400).never()).hasEnabledAutomation(anyString(), any(UUID.class),
                    any());
            verify(automationService, never()).hasEnabledAutomation(anyString(), any());
            verify(bufferService, never()).record(anyString(), anyString(), any(), any(), any());
        }
    }
}
