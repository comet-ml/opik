package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.events.CommentsCreated;
import com.comet.opik.api.events.CommentsDeleted;
import com.comet.opik.api.events.CommentsUpdated;
import com.comet.opik.api.events.ExperimentItemsCreated;
import com.comet.opik.api.events.ExperimentItemsDeleted;
import com.comet.opik.api.events.ExperimentUpdated;
import com.comet.opik.api.events.FeedbackScoresCreated;
import com.comet.opik.api.events.FeedbackScoresDeleted;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.api.events.SpansDeleted;
import com.comet.opik.api.events.SpansUpdated;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.ExperimentItemRef;
import com.comet.opik.domain.ExperimentItemService;
import com.comet.opik.domain.ExperimentTraceRef;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesService;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregationPublisher;
import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentAggregateEventListenerTest {

    private static final int AWAIT_TIMEOUT_SECONDS = 2;
    private static final String WORKSPACE_ID = "workspace-test";
    private static final String USER_NAME = "user-test";

    @Mock
    private ExperimentItemService experimentItemService;

    @Mock
    private ExperimentAggregationPublisher publisher;

    @Mock
    private ExperimentAggregatesService experimentAggregatesService;

    @Mock
    private ExperimentDenormalizationConfig config;

    @InjectMocks
    private ExperimentAggregateEventListener listener;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @BeforeEach
    void setUp() {
        lenient().when(publisher.publish(any(), anyString(), anyString())).thenReturn(Mono.empty());
    }

    @Nested
    class OnExperimentUpdated {

        @Test
        void publishWhenStatusIsCompleted() {
            when(config.isEnabled()).thenReturn(true);
            var experimentId = UUID.randomUUID();

            listener.onExperimentUpdated(
                    new ExperimentUpdated(experimentId, ExperimentStatus.COMPLETED, WORKSPACE_ID, USER_NAME));

            verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME);
        }

        @Test
        void publishWhenStatusIsCancelled() {
            when(config.isEnabled()).thenReturn(true);
            var experimentId = UUID.randomUUID();

            listener.onExperimentUpdated(
                    new ExperimentUpdated(experimentId, ExperimentStatus.CANCELLED, WORKSPACE_ID, USER_NAME));

            verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME);
        }

        @Test
        void doesNotPublishWhenStatusIsRunning() {
            when(config.isEnabled()).thenReturn(true);

            listener.onExperimentUpdated(
                    new ExperimentUpdated(UUID.randomUUID(), ExperimentStatus.RUNNING, WORKSPACE_ID, USER_NAME));

            verify(publisher, never()).publish(any(), anyString(), anyString());
        }

        @Test
        void doesNotPublishWhenConfigDisabled() {
            when(config.isEnabled()).thenReturn(false);

            listener.onExperimentUpdated(
                    new ExperimentUpdated(UUID.randomUUID(), ExperimentStatus.COMPLETED, WORKSPACE_ID, USER_NAME));

            verify(publisher, never()).publish(any(), anyString(), anyString());
        }
    }

    @Nested
    class OnExperimentItemsCreated {

        @Test
        void publishWhenFinishedExperimentsFound() {
            when(config.isEnabled()).thenReturn(true);
            var experimentId = UUID.randomUUID();
            when(experimentItemService.filterExperimentIdsByStatus(eq(Set.of(experimentId)),
                    eq(ExperimentAggregateEventListener.FINISHED_STATUSES)))
                    .thenReturn(Flux.just(experimentId));

            listener.onExperimentItemsCreated(
                    new ExperimentItemsCreated(Set.of(experimentId), WORKSPACE_ID, USER_NAME));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME));
        }

        @Test
        void doesNotPublishWhenNoFinishedExperimentsFound() {
            when(config.isEnabled()).thenReturn(true);
            var experimentId = UUID.randomUUID();
            when(experimentItemService.filterExperimentIdsByStatus(eq(Set.of(experimentId)),
                    eq(ExperimentAggregateEventListener.FINISHED_STATUSES)))
                    .thenReturn(Flux.empty());

            listener.onExperimentItemsCreated(
                    new ExperimentItemsCreated(Set.of(experimentId), WORKSPACE_ID, USER_NAME));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(publisher, never()).publish(any(), anyString(), anyString()));
        }

        @Test
        void doesNotCallServiceWhenConfigDisabled() {
            when(config.isEnabled()).thenReturn(false);

            listener.onExperimentItemsCreated(
                    new ExperimentItemsCreated(Set.of(UUID.randomUUID()), WORKSPACE_ID, USER_NAME));

            verify(experimentItemService, never()).filterExperimentIdsByStatus(any(), any());
            verify(publisher, never()).publish(any(), anyString(), anyString());
        }
    }

    @Nested
    class OnExperimentItemsDeleted {

        @Test
        void publishWhenFinishedExperimentsFound() {
            when(config.isEnabled()).thenReturn(true);
            var experimentId = UUID.randomUUID();
            var itemId = UUID.randomUUID();
            when(experimentItemService.filterExperimentIdsByStatus(eq(Set.of(experimentId)),
                    eq(ExperimentAggregateEventListener.FINISHED_STATUSES)))
                    .thenReturn(Flux.just(experimentId));
            when(experimentAggregatesService.deleteItemAggregatesByItemIds(eq(experimentId), eq(Set.of(itemId))))
                    .thenReturn(Mono.just(0L));

            listener.onExperimentItemsDeleted(
                    new ExperimentItemsDeleted(Set.of(new ExperimentItemRef(experimentId, itemId)),
                            WORKSPACE_ID, USER_NAME));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME));
        }

    }

    @Nested
    class OnTracesCreated {

        @Test
        void publishWhenExperimentRefsFound() {
            when(config.isEnabled()).thenReturn(true);
            var traceId = UUID.randomUUID();
            var projectId = UUID.randomUUID();
            var experimentId = UUID.randomUUID();
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder().id(traceId).projectId(projectId).build();
            when(experimentItemService.getExperimentRefsByTraceIds(eq(Set.of(traceId)),
                    eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                    .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, traceId)));

            listener.onTracesCreated(new TracesCreated(List.of(trace), WORKSPACE_ID, USER_NAME));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME));
        }

        @Test
        void doesNotPublishWhenNoExperimentRefsFound() {
            when(config.isEnabled()).thenReturn(true);
            var traceId = UUID.randomUUID();
            var projectId = UUID.randomUUID();
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder().id(traceId).projectId(projectId).build();
            when(experimentItemService.getExperimentRefsByTraceIds(eq(Set.of(traceId)),
                    eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                    .thenReturn(Flux.empty());

            listener.onTracesCreated(new TracesCreated(List.of(trace), WORKSPACE_ID, USER_NAME));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(publisher, never()).publish(any(), anyString(), anyString()));
        }

        @Test
        void doesNotCallServiceWhenTracesListEmpty() {
            listener.onTracesCreated(new TracesCreated(List.of(), WORKSPACE_ID, USER_NAME));

            verify(experimentItemService, never()).getExperimentRefsByTraceIds(any(), any(), any());
            verify(publisher, never()).publish(any(), anyString(), anyString());
        }

        @Test
        void doesNotCallServiceWhenConfigDisabled() {
            when(config.isEnabled()).thenReturn(false);
            var trace = podamFactory.manufacturePojo(Trace.class);

            listener.onTracesCreated(new TracesCreated(List.of(trace), WORKSPACE_ID, USER_NAME));

            verify(experimentItemService, never()).getExperimentRefsByTraceIds(any(), any(), any());
            verify(publisher, never()).publish(any(), anyString(), anyString());
        }
    }

    @Nested
    class OnTracesUpdated {

        @Test
        void publishWhenExperimentRefsFound() {
            when(config.isEnabled()).thenReturn(true);
            var traceId = UUID.randomUUID();
            var projectId = UUID.randomUUID();
            var experimentId = UUID.randomUUID();
            when(experimentItemService.getExperimentRefsByTraceIds(eq(Set.of(traceId)),
                    eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                    .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, traceId)));

            listener.onTracesUpdated(
                    new TracesUpdated(Set.of(projectId), Set.of(traceId), WORKSPACE_ID, USER_NAME,
                            TraceUpdate.builder().build(), null, Map.of(traceId, projectId)));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME));
        }

        @Test
        void doesNotCallServiceWhenTraceIdsEmpty() {
            listener.onTracesUpdated(
                    new TracesUpdated(Set.of(UUID.randomUUID()), Set.of(), WORKSPACE_ID, USER_NAME,
                            TraceUpdate.builder().build()));

            verify(experimentItemService, never()).getExperimentRefsByTraceIds(any(), any(), any());
            verify(publisher, never()).publish(any(), anyString(), anyString());
        }
    }

    @Nested
    class OnTracesDeleted {

        @Test
        void publishWhenExperimentRefsFound() {
            when(config.isEnabled()).thenReturn(true);
            var traceId = UUID.randomUUID();
            var projectId = UUID.randomUUID();
            var experimentId = UUID.randomUUID();
            when(experimentItemService.getExperimentRefsByTraceIds(eq(Set.of(traceId)),
                    eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                    .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, traceId)));

            listener.onTracesDeleted(new TracesDeleted(Set.of(traceId), projectId, WORKSPACE_ID, USER_NAME));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME));
        }

        @Test
        void doesNotCallServiceWhenTraceIdsEmpty() {
            listener.onTracesDeleted(new TracesDeleted(Set.of(), null, WORKSPACE_ID, USER_NAME));

            verify(experimentItemService, never()).getExperimentRefsByTraceIds(any(), any(), any());
            verify(publisher, never()).publish(any(), anyString(), anyString());
        }
    }

    @Nested
    class OnSpansCreated {

        @Test
        void publishWhenExperimentRefsFound() {
            when(config.isEnabled()).thenReturn(true);
            var traceId = UUID.randomUUID();
            var projectId = UUID.randomUUID();
            var experimentId = UUID.randomUUID();
            var span = podamFactory.manufacturePojo(Span.class).toBuilder().traceId(traceId).projectId(projectId)
                    .build();
            when(experimentItemService.getExperimentRefsByTraceIds(eq(Set.of(traceId)),
                    eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                    .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, traceId)));

            listener.onSpansCreated(new SpansCreated(List.of(span), WORKSPACE_ID, USER_NAME));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME));
        }

        @Test
        void doesNotCallServiceWhenSpanListEmpty() {
            listener.onSpansCreated(new SpansCreated(List.of(), WORKSPACE_ID, USER_NAME));

            verify(experimentItemService, never()).getExperimentRefsByTraceIds(any(), any(), any());
            verify(publisher, never()).publish(any(), anyString(), anyString());
        }
    }

    @Nested
    class OnSpansUpdated {

        @Test
        void publishWhenExperimentRefsFound() {
            when(config.isEnabled()).thenReturn(true);
            var traceId = UUID.randomUUID();
            var experimentId = UUID.randomUUID();
            // SpansUpdated carries no project, so the listener threads a null projectId (skip-index backstop).
            when(experimentItemService.getExperimentRefsByTraceIds(eq(Set.of(traceId)),
                    eq(ExperimentAggregateEventListener.FINISHED_STATUSES), isNull()))
                    .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, traceId)));

            listener.onSpansUpdated(new SpansUpdated(Set.of(traceId), WORKSPACE_ID, USER_NAME));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME));
        }

        @Test
        void doesNotCallServiceWhenTraceIdsEmpty() {
            listener.onSpansUpdated(new SpansUpdated(Set.of(), WORKSPACE_ID, USER_NAME));

            verify(experimentItemService, never()).getExperimentRefsByTraceIds(any(), any(), any());
            verify(publisher, never()).publish(any(), anyString(), anyString());
        }
    }

    @Nested
    class OnSpansDeleted {

        @Test
        void publishWhenExperimentRefsFound() {
            when(config.isEnabled()).thenReturn(true);
            var traceId = UUID.randomUUID();
            var projectId = UUID.randomUUID();
            var experimentId = UUID.randomUUID();
            when(experimentItemService.getExperimentRefsByTraceIds(eq(Set.of(traceId)),
                    eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                    .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, traceId)));

            listener.onSpansDeleted(
                    new SpansDeleted(Set.of(UUID.randomUUID()), Set.of(traceId), WORKSPACE_ID, USER_NAME, projectId));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME));
        }

        @Test
        void doesNotCallServiceWhenTraceIdsEmpty() {
            listener.onSpansDeleted(new SpansDeleted(Set.of(UUID.randomUUID()), Set.of(), WORKSPACE_ID, USER_NAME));

            verify(experimentItemService, never()).getExperimentRefsByTraceIds(any(), any(), any());
            verify(publisher, never()).publish(any(), anyString(), anyString());
        }
    }

    // -------------------------------------------------------------------
    // Entity-type based handlers: FeedbackScores and Comments
    // Each is parameterized over EntityType × configEnabled, covering:
    //   TRACE  + enabled=true  → publishes
    //   TRACE  + enabled=false → does not publish
    //   SPAN   + enabled=true  → publishes
    //   SPAN   + enabled=false → does not publish
    //   THREAD + enabled=true  → does not publish (unsupported entity type)
    //   THREAD + enabled=false → does not publish
    // -------------------------------------------------------------------

    static Stream<Arguments> entityTypeAndConfigEnabledCases() {
        return Stream.of(
                Arguments.of(EntityType.TRACE, true),
                Arguments.of(EntityType.TRACE, false),
                Arguments.of(EntityType.SPAN, true),
                Arguments.of(EntityType.SPAN, false),
                Arguments.of(EntityType.THREAD, true),
                Arguments.of(EntityType.THREAD, false));
    }

    @Nested
    class OnFeedbackScoresCreated {

        @ParameterizedTest(name = "entityType={0}, configEnabled={1}")
        @MethodSource("com.comet.opik.api.resources.v1.events.ExperimentAggregateEventListenerTest#entityTypeAndConfigEnabledCases")
        void handlesFeedbackScoresCreated(EntityType entityType, boolean configEnabled) {
            when(config.isEnabled()).thenReturn(configEnabled);

            var entityId = UUID.randomUUID();
            var experimentId = UUID.randomUUID();
            boolean expectPublish = configEnabled && entityType != EntityType.THREAD;
            var projectId = UUID.randomUUID();

            if (expectPublish) {
                if (entityType == EntityType.TRACE) {
                    when(experimentItemService.getExperimentRefsByTraceIds(eq(Set.of(entityId)),
                            eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                            .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, entityId)));
                } else {
                    when(experimentItemService.getExperimentRefsBySpanIds(eq(Set.of(entityId)),
                            eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                            .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, UUID.randomUUID())));
                }
            }

            listener.onFeedbackScoresCreated(
                    new FeedbackScoresCreated(Set.of(entityId), entityType, WORKSPACE_ID, USER_NAME, projectId));

            if (expectPublish) {
                await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .untilAsserted(
                                () -> verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME));
            } else {
                await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .untilAsserted(
                                () -> verify(publisher, never()).publish(any(), anyString(), anyString()));
            }
        }
    }

    @Nested
    class OnFeedbackScoresDeleted {

        @ParameterizedTest(name = "entityType={0}, configEnabled={1}")
        @MethodSource("com.comet.opik.api.resources.v1.events.ExperimentAggregateEventListenerTest#entityTypeAndConfigEnabledCases")
        void handlesFeedbackScoresDeleted(EntityType entityType, boolean configEnabled) {
            when(config.isEnabled()).thenReturn(configEnabled);

            var entityId = UUID.randomUUID();
            var experimentId = UUID.randomUUID();
            boolean expectPublish = configEnabled && entityType != EntityType.THREAD;
            var projectId = UUID.randomUUID();

            if (expectPublish) {
                if (entityType == EntityType.TRACE) {
                    when(experimentItemService.getExperimentRefsByTraceIds(eq(Set.of(entityId)),
                            eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                            .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, entityId)));
                } else {
                    when(experimentItemService.getExperimentRefsBySpanIds(eq(Set.of(entityId)),
                            eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                            .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, UUID.randomUUID())));
                }
            }

            listener.onFeedbackScoresDeleted(
                    new FeedbackScoresDeleted(Set.of(entityId), entityType, WORKSPACE_ID, USER_NAME, projectId));

            if (expectPublish) {
                await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .untilAsserted(
                                () -> verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME));
            } else {
                await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .untilAsserted(
                                () -> verify(publisher, never()).publish(any(), anyString(), anyString()));
            }
        }
    }

    @Nested
    class OnCommentsCreated {

        @ParameterizedTest(name = "entityType={0}, configEnabled={1}")
        @MethodSource("com.comet.opik.api.resources.v1.events.ExperimentAggregateEventListenerTest#entityTypeAndConfigEnabledCases")
        void handlesCommentsCreated(EntityType entityType, boolean configEnabled) {
            when(config.isEnabled()).thenReturn(configEnabled);

            var entityId = UUID.randomUUID();
            var experimentId = UUID.randomUUID();
            boolean expectPublish = configEnabled && entityType != EntityType.THREAD;
            var projectId = UUID.randomUUID();

            if (expectPublish) {
                if (entityType == EntityType.TRACE) {
                    when(experimentItemService.getExperimentRefsByTraceIds(eq(Set.of(entityId)),
                            eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                            .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, entityId)));
                } else {
                    when(experimentItemService.getExperimentRefsBySpanIds(eq(Set.of(entityId)),
                            eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                            .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, UUID.randomUUID())));
                }
            }

            listener.onCommentsCreated(
                    new CommentsCreated(Set.of(entityId), entityType, WORKSPACE_ID, USER_NAME, projectId));

            if (expectPublish) {
                await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .untilAsserted(
                                () -> verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME));
            } else {
                await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .untilAsserted(
                                () -> verify(publisher, never()).publish(any(), anyString(), anyString()));
            }
        }
    }

    @Nested
    class OnCommentsUpdated {

        @ParameterizedTest(name = "entityType={0}, configEnabled={1}")
        @MethodSource("com.comet.opik.api.resources.v1.events.ExperimentAggregateEventListenerTest#entityTypeAndConfigEnabledCases")
        void handlesCommentsUpdated(EntityType entityType, boolean configEnabled) {
            when(config.isEnabled()).thenReturn(configEnabled);

            var entityId = UUID.randomUUID();
            var experimentId = UUID.randomUUID();
            boolean expectPublish = configEnabled && entityType != EntityType.THREAD;
            var projectId = UUID.randomUUID();

            if (expectPublish) {
                if (entityType == EntityType.TRACE) {
                    when(experimentItemService.getExperimentRefsByTraceIds(eq(Set.of(entityId)),
                            eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                            .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, entityId)));
                } else {
                    when(experimentItemService.getExperimentRefsBySpanIds(eq(Set.of(entityId)),
                            eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                            .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, UUID.randomUUID())));
                }
            }

            listener.onCommentsUpdated(
                    new CommentsUpdated(Set.of(entityId), entityType, WORKSPACE_ID, USER_NAME, projectId));

            if (expectPublish) {
                await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .untilAsserted(
                                () -> verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME));
            } else {
                await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .untilAsserted(
                                () -> verify(publisher, never()).publish(any(), anyString(), anyString()));
            }
        }
    }

    @Nested
    class OnCommentsDeleted {

        @ParameterizedTest(name = "entityType={0}, configEnabled={1}")
        @MethodSource("com.comet.opik.api.resources.v1.events.ExperimentAggregateEventListenerTest#entityTypeAndConfigEnabledCases")
        void handlesCommentsDeleted(EntityType entityType, boolean configEnabled) {
            when(config.isEnabled()).thenReturn(configEnabled);

            var entityId = UUID.randomUUID();
            var experimentId = UUID.randomUUID();
            boolean expectPublish = configEnabled && entityType != EntityType.THREAD;
            var projectId = UUID.randomUUID();

            if (expectPublish) {
                if (entityType == EntityType.TRACE) {
                    when(experimentItemService.getExperimentRefsByTraceIds(eq(Set.of(entityId)),
                            eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                            .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, entityId)));
                } else {
                    when(experimentItemService.getExperimentRefsBySpanIds(eq(Set.of(entityId)),
                            eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)))
                            .thenReturn(Flux.just(new ExperimentTraceRef(experimentId, UUID.randomUUID())));
                }
            }

            listener.onCommentsDeleted(
                    new CommentsDeleted(Set.of(entityId), entityType, WORKSPACE_ID, USER_NAME, projectId));

            if (expectPublish) {
                await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .untilAsserted(
                                () -> verify(publisher).publish(Set.of(experimentId), WORKSPACE_ID, USER_NAME));
            } else {
                await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .untilAsserted(
                                () -> verify(publisher, never()).publish(any(), anyString(), anyString()));
            }
        }
    }

    @Nested
    class ProjectSplittingAndThreading {

        @Test
        void splitsTraceIdsPerProjectOnTracesCreated() {
            when(config.isEnabled()).thenReturn(true);
            when(experimentItemService.getExperimentRefsByTraceIds(any(), any(), any())).thenReturn(Flux.empty());

            var projectA = UUID.randomUUID();
            var projectB = UUID.randomUUID();
            var t1 = traceIn(projectA);
            var t2 = traceIn(projectA);
            var t3 = traceIn(projectB);

            listener.onTracesCreated(new TracesCreated(List.of(t1, t2, t3), WORKSPACE_ID, USER_NAME));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).untilAsserted(() -> {
                verify(experimentItemService)
                        .getExperimentRefsByTraceIds(eq(Set.of(t1.id(), t2.id())),
                                eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectA));
                verify(experimentItemService)
                        .getExperimentRefsByTraceIds(eq(Set.of(t3.id())),
                                eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectB));
                verify(experimentItemService, times(2)).getExperimentRefsByTraceIds(any(), any(), any());
            });
        }

        @Test
        void splitsTraceIdsPerProjectOnSpansCreated() {
            when(config.isEnabled()).thenReturn(true);
            when(experimentItemService.getExperimentRefsByTraceIds(any(), any(), any())).thenReturn(Flux.empty());

            var projectA = UUID.randomUUID();
            var projectB = UUID.randomUUID();
            var traceA = UUID.randomUUID();
            var traceB = UUID.randomUUID();

            listener.onSpansCreated(new SpansCreated(
                    List.of(spanIn(projectA, traceA), spanIn(projectB, traceB)), WORKSPACE_ID, USER_NAME));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).untilAsserted(() -> {
                verify(experimentItemService).getExperimentRefsByTraceIds(eq(Set.of(traceA)),
                        eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectA));
                verify(experimentItemService).getExperimentRefsByTraceIds(eq(Set.of(traceB)),
                        eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectB));
            });
        }

        @Test
        void splitsTraceIdsPerProjectOnTracesUpdatedUsingMapping() {
            when(config.isEnabled()).thenReturn(true);
            when(experimentItemService.getExperimentRefsByTraceIds(any(), any(), any())).thenReturn(Flux.empty());

            var projectA = UUID.randomUUID();
            var projectB = UUID.randomUUID();
            var traceA = UUID.randomUUID();
            var traceB = UUID.randomUUID();

            listener.onTracesUpdated(new TracesUpdated(Set.of(projectA, projectB), Set.of(traceA, traceB),
                    WORKSPACE_ID, USER_NAME, TraceUpdate.builder().build(), null,
                    Map.of(traceA, projectA, traceB, projectB)));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).untilAsserted(() -> {
                verify(experimentItemService).getExperimentRefsByTraceIds(eq(Set.of(traceA)),
                        eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectA));
                verify(experimentItemService).getExperimentRefsByTraceIds(eq(Set.of(traceB)),
                        eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectB));
            });
        }

        @Test
        void routesTraceIdsNotCoveredByMappingThroughFallbackOnTracesUpdated() {
            when(config.isEnabled()).thenReturn(true);
            when(experimentItemService.getExperimentRefsByTraceIds(any(), any(), any())).thenReturn(Flux.empty());

            var projectA = UUID.randomUUID();
            var mappedTraceId = UUID.randomUUID();
            var unmappedTraceId = UUID.randomUUID();

            // The event carries both trace ids, but the mapping only covers one of them.
            listener.onTracesUpdated(new TracesUpdated(Set.of(projectA), Set.of(mappedTraceId, unmappedTraceId),
                    WORKSPACE_ID, USER_NAME, TraceUpdate.builder().build(), null, Map.of(mappedTraceId, projectA)));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).untilAsserted(() -> {
                // Mapped id is pruned by its project; the uncovered id still runs via the workspace-scoped fallback.
                verify(experimentItemService).getExperimentRefsByTraceIds(eq(Set.of(mappedTraceId)), any(),
                        eq(projectA));
                verify(experimentItemService).getExperimentRefsByTraceIds(eq(Set.of(unmappedTraceId)),
                        eq(ExperimentAggregateEventListener.FINISHED_STATUSES), isNull());
            });
        }

        @Test
        void passesEventProjectIdOnTracesDeleted() {
            when(config.isEnabled()).thenReturn(true);
            when(experimentItemService.getExperimentRefsByTraceIds(any(), any(), any())).thenReturn(Flux.empty());

            var projectId = UUID.randomUUID();
            var traceId = UUID.randomUUID();

            listener.onTracesDeleted(new TracesDeleted(Set.of(traceId), projectId, WORKSPACE_ID, USER_NAME));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).untilAsserted(() -> verify(experimentItemService)
                    .getExperimentRefsByTraceIds(eq(Set.of(traceId)),
                            eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)));
        }

        @Test
        void passesEventProjectIdOnSpansDeleted() {
            when(config.isEnabled()).thenReturn(true);
            when(experimentItemService.getExperimentRefsByTraceIds(any(), any(), any())).thenReturn(Flux.empty());

            var projectId = UUID.randomUUID();
            var traceId = UUID.randomUUID();

            listener.onSpansDeleted(
                    new SpansDeleted(Set.of(UUID.randomUUID()), Set.of(traceId), WORKSPACE_ID, USER_NAME, projectId));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).untilAsserted(() -> verify(experimentItemService)
                    .getExperimentRefsByTraceIds(eq(Set.of(traceId)),
                            eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)));
        }

        @Test
        void passesEventProjectIdOnSpanFeedbackScore() {
            when(config.isEnabled()).thenReturn(true);
            when(experimentItemService.getExperimentRefsBySpanIds(any(), any(), any())).thenReturn(Flux.empty());

            var projectId = UUID.randomUUID();
            var spanId = UUID.randomUUID();

            listener.onFeedbackScoresCreated(
                    new FeedbackScoresCreated(Set.of(spanId), EntityType.SPAN, WORKSPACE_ID, USER_NAME, projectId));

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).untilAsserted(() -> verify(experimentItemService)
                    .getExperimentRefsBySpanIds(eq(Set.of(spanId)),
                            eq(ExperimentAggregateEventListener.FINISHED_STATUSES), eq(projectId)));
        }

        private Trace traceIn(UUID projectId) {
            return podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .id(UUID.randomUUID())
                    .projectId(projectId)
                    .build();
        }

        private Span spanIn(UUID projectId, UUID traceId) {
            return podamFactory.manufacturePojo(Span.class).toBuilder()
                    .id(UUID.randomUUID())
                    .traceId(traceId)
                    .projectId(projectId)
                    .build();
        }
    }
}
