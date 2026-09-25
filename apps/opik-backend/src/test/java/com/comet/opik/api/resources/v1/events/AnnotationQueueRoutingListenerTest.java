package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.FeedbackScoresCreated;
import com.comet.opik.domain.AnnotationQueueAutomationService;
import com.comet.opik.domain.AnnotationQueueRoutingBufferService;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
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

import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.AnnotationQueue.AnnotationScope;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the guards, and above all what they let through: the listener is the feature's only volume
 * control, so a guard that stops working means every score event in the deployment reaches the buffer.
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
    private AnnotationQueueRoutingConfig config;

    private AnnotationQueueRoutingListener listener;

    private String workspaceId;
    private String userName;

    @BeforeEach
    void setUp() {
        workspaceId = randomString();
        userName = randomString();
        when(config.isEnabled()).thenReturn(true);
        when(bufferService.add(anyString(), any(), any())).thenReturn(Mono.empty());
        listener = new AnnotationQueueRoutingListener(automationService, bufferService, config);
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
    }

    @Nested
    @DisplayName("Skipped")
    class Skipped {

        @Test
        void publishesNothingWhenRoutingIsDisabled() {
            // The guard below is a database round trip on the busiest event in the system, so a disabled
            // feature must not reach it either.
            when(config.isEnabled()).thenReturn(false);

            listener.onFeedbackScoresCreated(event(EntityType.TRACE, ID_GENERATOR.generateId(),
                    Set.of(ID_GENERATOR.generateId())));

            assertNothingRouted();
        }

        @Test
        void publishesNothingForSpans() {
            listener.onFeedbackScoresCreated(event(EntityType.SPAN, ID_GENERATOR.generateId(),
                    Set.of(ID_GENERATOR.generateId())));

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
