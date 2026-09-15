package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpansCountResponse;
import com.comet.opik.api.error.InvalidUUIDException;
import com.comet.opik.api.events.SpansDeleted;
import com.comet.opik.domain.attachment.AttachmentReinjectorService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.attachment.AttachmentStripperService;
import com.comet.opik.domain.utils.DemoDataExclusionUtils.WorkspaceProjectCount;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.UuidValidationConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TestUuidV7TimestampValidatorFactory;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.eventbus.EventBus;
import io.dropwizard.util.Duration;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.domain.ProjectService.DEFAULT_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpanServiceImplTest {

    private static final LockService DUMMY_LOCK_SERVICE = new DummyLockService();
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private SpanService spanService;

    @Mock
    private SpanDAO spanDAO;

    @Mock
    private ProjectService projectService;

    @Mock
    private CommentService commentService;

    @Mock
    private FeedbackScoreService feedbackScoreService;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private AttachmentStripperService attachmentStripperService;

    @Mock
    private AttachmentReinjectorService attachmentReinjectorService;

    @Mock
    private EventBus eventBus;

    @Mock
    private DeletionEventDAO deletionEventDAO;

    private final IdGenerator idGenerator = TestIdGeneratorFactory.create();

    private SpanService newSpanService(DatabaseAnalyticsDataModelConfig databaseAnalyticsDataModelConfig) {
        return newSpanService(databaseAnalyticsDataModelConfig, idGenerator);
    }

    private SpanService newSpanService(DatabaseAnalyticsDataModelConfig databaseAnalyticsDataModelConfig,
            IdGenerator idGenerator) {
        var opikConfiguration = new OpikConfiguration();
        opikConfiguration.setDatabaseAnalyticsDataModel(databaseAnalyticsDataModelConfig);
        return new SpanService(
                spanDAO,
                projectService,
                idGenerator,
                DUMMY_LOCK_SERVICE,
                commentService,
                feedbackScoreService,
                attachmentService,
                attachmentStripperService,
                attachmentReinjectorService,
                eventBus,
                deletionEventDAO,
                opikConfiguration);
    }

    @Nested
    @DisplayName("Delete Spans (trace-delete cascade):")
    class DeleteSpans {

        @Test
        @DisplayName("when capture is disabled, then delete records no deletion events")
        void delete__whenCaptureDisabled__thenRecordsNoDeletionEvents() {
            var traceIds = Set.of(idGenerator.generateId());
            var spanIds = Set.of(idGenerator.generateId(), idGenerator.generateId());
            var projectId = idGenerator.generateId();
            var workspaceId = UUID.randomUUID().toString();
            mockSpanDeleteFlow(traceIds, spanIds, projectId);
            when(spanDAO.deleteByIds(spanIds, projectId)).thenReturn(Mono.just((long) spanIds.size()));

            // spanService is built with capture disabled (default config)
            spanService = newSpanService(DatabaseAnalyticsDataModelConfig.builder().build());
            assertDoesNotThrow(() -> spanService
                    .deleteByTraceIds(traceIds, projectId)
                    .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, DEFAULT_USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .block());

            verify(spanDAO).deleteByIds(spanIds, projectId);
            verify(eventBus).post(any(SpansDeleted.class));
            verifyNoInteractions(deletionEventDAO);
        }

        @Test
        @DisplayName("when capture is enabled and recording fails, then the delete still succeeds")
        void delete__whenCaptureEnabledAndRecordingFails__thenDeleteSucceeds() {
            var traceIds = Set.of(idGenerator.generateId());
            var spanIds = Set.of(idGenerator.generateId(), idGenerator.generateId());
            var projectId = idGenerator.generateId();
            var workspaceId = UUID.randomUUID().toString();
            mockSpanDeleteFlow(traceIds, spanIds, projectId);
            when(spanDAO.deleteByIds(spanIds, projectId)).thenReturn(Mono.just((long) spanIds.size()));
            when(deletionEventDAO.insert(deletionEvents(projectId, spanIds, workspaceId), DEFAULT_USER))
                    .thenReturn(Mono.error(new RuntimeException("Error inserting deletion events")));

            spanService = newSpanService(DatabaseAnalyticsDataModelConfig.builder()
                    .spanDeletionEventsCaptureEnabled(true)
                    .build());
            // Capture is best-effort: its failure is swallowed and must not fail the deletion.
            assertDoesNotThrow(() -> spanService
                    .deleteByTraceIds(traceIds, projectId)
                    .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, DEFAULT_USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .block());

            verify(spanDAO).deleteByIds(spanIds, projectId);
            verify(eventBus).post(any(SpansDeleted.class));
            verify(deletionEventDAO).insert(deletionEvents(projectId, spanIds, workspaceId), DEFAULT_USER);
        }

        @Test
        @DisplayName("when the delete fails, then the deletion events are still recorded and the error propagates")
        void delete__whenDeleteFails__thenStillRecordsDeletionEventsAndPropagates() {
            var traceIds = Set.of(idGenerator.generateId());
            var spanIds = Set.of(idGenerator.generateId(), idGenerator.generateId());
            var projectId = idGenerator.generateId();
            var workspaceId = UUID.randomUUID().toString();
            mockSpanDeleteFlow(traceIds, spanIds, projectId);
            when(spanDAO.deleteByIds(spanIds, projectId))
                    .thenReturn(Mono.error(new RuntimeException("Error deleting spans")));
            when(deletionEventDAO.insert(deletionEvents(projectId, spanIds, workspaceId), DEFAULT_USER))
                    .thenReturn(Mono.empty());

            spanService = newSpanService(DatabaseAnalyticsDataModelConfig.builder()
                    .spanDeletionEventsCaptureEnabled(true)
                    .build());
            // OPIK-8141, cascade side: capture runs before the delete, so a failed delete is recorded anyway.
            assertThatThrownBy(() -> spanService
                    .deleteByTraceIds(traceIds, projectId)
                    .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, DEFAULT_USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .block())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Error deleting spans");

            // The ordering is the fix, so assert it rather than infer it from both having happened.
            var inOrder = inOrder(deletionEventDAO, spanDAO);
            inOrder.verify(deletionEventDAO).insert(deletionEvents(projectId, spanIds, workspaceId), DEFAULT_USER);
            inOrder.verify(spanDAO).deleteByIds(spanIds, projectId);
            verifyNoInteractions(eventBus);
        }

        @Test
        @DisplayName("when the trace has no spans, then nothing is deleted or captured and the delete succeeds")
        void delete__whenTraceHasNoSpans__thenNoDeleteAndNoCapture() {
            var traceIds = Set.of(idGenerator.generateId());
            var projectId = idGenerator.generateId();
            var workspaceId = UUID.randomUUID().toString();
            when(spanDAO.getSpanIdsForTraces(traceIds, projectId)).thenReturn(Mono.just(Set.of()));

            spanService = newSpanService(DatabaseAnalyticsDataModelConfig.builder()
                    .spanDeletionEventsCaptureEnabled(true)
                    .build());
            // A spanless trace short-circuits before the delete/capture; it must still complete cleanly.
            assertDoesNotThrow(() -> spanService
                    .deleteByTraceIds(traceIds, projectId)
                    .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, DEFAULT_USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .block());

            verify(spanDAO).getSpanIdsForTraces(traceIds, projectId);
            verify(spanDAO, never()).deleteByIds(any(), any());
            verifyNoInteractions(deletionEventDAO, eventBus);
        }

        /**
         * The bridge rows the cascade is expected to record: one {@code spans} / {@code cascade} event per span id,
         * with {@code eventTime} left null for ClickHouse to stamp. Matching the insert on this rather than on
         * {@code any()} is what pins the recorded contents, so a wrong source table, reason or id fails the test.
         */
        private Set<DeletionEvent> deletionEvents(UUID projectId, Set<UUID> spanIds, String workspaceId) {
            return spanIds.stream()
                    .map(id -> DeletionEvent.builder()
                            .sourceTable(SourceTable.SPANS)
                            .workspaceId(workspaceId)
                            .projectId(projectId)
                            .deletedId(id.toString())
                            .deletionReason(DeletionReason.CASCADE)
                            .build())
                    .collect(Collectors.toUnmodifiableSet());
        }

        // Stubs the cascade steps that run before the span lightweight delete: id resolution and the
        // comment/feedback-score/attachment deletes. deleteByIds and deletionEventDAO are stubbed per-test.
        private void mockSpanDeleteFlow(Set<UUID> traceIds, Set<UUID> spanIds, UUID projectId) {
            when(spanDAO.getSpanIdsForTraces(traceIds, projectId)).thenReturn(Mono.just(spanIds));
            when(commentService.deleteByEntityIds(any(), eq(spanIds), eq(projectId))).thenReturn(Mono.just(0L));
            when(feedbackScoreService.deleteBySpanIds(spanIds, projectId)).thenReturn(Mono.empty());
            when(attachmentService.deleteByEntityIds(any(), eq(spanIds), eq(projectId))).thenReturn(Mono.just(0L));
        }
    }

    /**
     * OPIK-7794: span reference ids (traceId / parentSpanId) are validated with the request workspace, so
     * an allow-listed demo workspace can create a future-dated span pointing at its own future-dated
     * trace. Every other workspace keeps the default not-in-future policy on those references.
     */
    @Nested
    class SpanReferenceValidation {

        private static final String BYPASS_WORKSPACE_ID = UUID.randomUUID().toString();
        private static final String OTHER_WORKSPACE_ID = UUID.randomUUID().toString();
        private static final String PROJECT_NAME = RandomStringUtils.secure().nextAlphanumeric(32);
        /**
         * Offset outside the default window but inside the bypass one.
         */
        private static final int WITHIN_BYPASS_WINDOW_DAYS = 10;

        private final IdGenerator bypassIdGenerator = new IdGeneratorImpl(TestUuidV7TimestampValidatorFactory.create(
                UuidValidationConfig.builder()
                        .enabled(true)
                        .auditOnly(false)
                        .window(Duration.hours(24))
                        .bypassWindow(Duration.days(30))
                        .build(),
                BYPASS_WORKSPACE_ID));

        private final UUID projectId = bypassIdGenerator.generateId();
        private final Project project = Project.builder().id(projectId).name(PROJECT_NAME).build();

        @Test
        void createAcceptsAFutureDatedTraceReferenceForTheAllowListedWorkspace() {
            var span = futureDatedSpan();
            var expectedSpan = span.toBuilder().projectId(projectId).build();
            when(spanDAO.getPartialById(span.id())).thenReturn(Mono.empty());
            when(attachmentStripperService.stripAttachments(expectedSpan, BYPASS_WORKSPACE_ID, DEFAULT_USER,
                    PROJECT_NAME)).thenReturn(Mono.just(expectedSpan));
            when(spanDAO.insert(expectedSpan)).thenReturn(Mono.empty());

            var actualSpanId = create(span, BYPASS_WORKSPACE_ID).block();

            assertThat(actualSpanId).isEqualTo(span.id());
            verify(spanDAO).insert(expectedSpan);
        }

        @Test
        void createRejectsAFutureDatedTraceReferenceForAnyOtherWorkspace() {
            // StepVerifier rather than block(): the rejection must reach subscribers as an error signal,
            // so the downstream operators on the write paths still see it.
            StepVerifier.create(create(futureDatedSpan(), OTHER_WORKSPACE_ID))
                    .expectError(InvalidUUIDException.class)
                    .verify();

            verifyNoInteractions(spanDAO, attachmentStripperService, eventBus);
        }

        /**
         * A span and the trace it references, both dated past the default window but inside the bypass one.
         */
        private Span futureDatedSpan() {
            var futureMillis = Instant.now().plus(WITHIN_BYPASS_WINDOW_DAYS, ChronoUnit.DAYS).toEpochMilli();
            return Span.builder()
                    .id(bypassIdGenerator.getTimeOrderedEpoch(futureMillis))
                    .traceId(bypassIdGenerator.getTimeOrderedEpoch(futureMillis))
                    .projectName(PROJECT_NAME)
                    .build();
        }

        /**
         * Creates {@code span} as {@code workspaceId}. The project lookup is assembled eagerly by
         * {@code create}, so it is stubbed here rather than per test, including on the rejecting path.
         */
        private Mono<UUID> create(Span span, String workspaceId) {
            when(projectService.getOrCreate(PROJECT_NAME)).thenReturn(Mono.just(project));
            return newSpanService(DatabaseAnalyticsDataModelConfig.builder().build(), bypassIdGenerator)
                    .create(span)
                    .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, DEFAULT_USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId));
        }
    }

    /**
     * The demo-project exclusion is applied in this service rather than in the usage SQL, so what it does with the
     * DAO's per-project rows is behaviour worth pinning: drop demo projects, then either sum the rest into the
     * shape the endpoint returns or leave them at project granularity, depending on the endpoint. These counts are
     * what usage is billed on, so a fold that lost rows would under-bill and one that kept demo rows would
     * over-bill.
     */
    @Nested
    class DailyUsage {

        private static final String WORKSPACE_ID = UUID.randomUUID().toString();
        private static final String OTHER_WORKSPACE_ID = UUID.randomUUID().toString();
        private static final UUID REGULAR_PROJECT_ID = ID_GENERATOR.generateId();
        private static final UUID OTHER_REGULAR_PROJECT_ID = ID_GENERATOR.generateId();
        private static final UUID DEMO_PROJECT_ID = ID_GENERATOR.generateId();

        @Test
        void countSpansPerWorkspace__whenSeveralWorkspaces__thenEachIsFoldedOnItsOwn() {
            var regularCount = randomCount();
            var otherWorkspaceCount = randomCount();

            when(spanDAO.countSpansPerWorkspaceProject()).thenReturn(Flux.just(
                    workspaceProjectCount(WORKSPACE_ID, REGULAR_PROJECT_ID, regularCount),
                    workspaceProjectCount(WORKSPACE_ID, DEMO_PROJECT_ID, randomCount()),
                    workspaceProjectCount(OTHER_WORKSPACE_ID, OTHER_REGULAR_PROJECT_ID, otherWorkspaceCount)));
            when(projectService.getDemoProjectIdsInWorkspaces(Set.of(WORKSPACE_ID, OTHER_WORKSPACE_ID)))
                    .thenReturn(Mono.just(Set.of(DEMO_PROJECT_ID)));

            var actualResponse = spanService()
                    .countSpansPerWorkspace()
                    .block();

            assertThat(actualResponse.workspacesSpansCount())
                    .containsExactlyInAnyOrder(
                            SpansCountResponse.WorkspaceSpansCount.builder()
                                    .workspace(WORKSPACE_ID)
                                    .spanCount(Math.toIntExact(regularCount))
                                    .build(),
                            SpansCountResponse.WorkspaceSpansCount.builder()
                                    .workspace(OTHER_WORKSPACE_ID)
                                    .spanCount(Math.toIntExact(otherWorkspaceCount))
                                    .build());
        }

        @Test
        void countSpansPerWorkspace__whenNoSpans__thenReturnsEmptyResponseWithoutLookingUpDemoProjects() {
            when(spanDAO.countSpansPerWorkspaceProject()).thenReturn(Flux.empty());
            when(projectService.getDemoProjectIdsInWorkspaces(Set.of())).thenReturn(Mono.just(Set.of()));

            var actualResponse = spanService()
                    .countSpansPerWorkspace()
                    .block();

            assertThat(actualResponse).isEqualTo(SpansCountResponse.builder().workspacesSpansCount(List.of()).build());
        }

        /**
         * The bound the exclusion rests on: the demo lookup only ever sees the workspaces that had spans, which is
         * what keeps it independent of how many demo projects the installation holds.
         */
        @Test
        void countSpansPerWorkspace__whenFolding__thenTheDemoProjectLookupIsScopedToTheWorkspacesThatHadSpans() {
            when(spanDAO.countSpansPerWorkspaceProject()).thenReturn(Flux.just(
                    workspaceProjectCount(WORKSPACE_ID, REGULAR_PROJECT_ID, randomCount()),
                    workspaceProjectCount(WORKSPACE_ID, DEMO_PROJECT_ID, randomCount())));
            when(projectService.getDemoProjectIdsInWorkspaces(Set.of(WORKSPACE_ID)))
                    .thenReturn(Mono.just(Set.of(DEMO_PROJECT_ID)));

            spanService().countSpansPerWorkspace().block();

            verify(projectService).getDemoProjectIdsInWorkspaces(Set.of(WORKSPACE_ID));
        }

        /**
         * The failure this whole change exists to prevent, at the level that decides it. Every consumer of these
         * endpoints reports a failed collection as nothing collected, so a service that answered an empty response
         * instead of failing would reproduce the traces incident exactly: billing counting zero, no alert. Asserting
         * the same exception instance, not merely that something failed, is what rules out it being swallowed and
         * replaced.
         */
        @Test
        void countSpansPerWorkspace__whenTheQueryFails__thenTheErrorReachesTheCaller() {
            var failure = new IllegalStateException("TIMEOUT_EXCEEDED");
            when(spanDAO.countSpansPerWorkspaceProject()).thenReturn(Flux.error(failure));

            StepVerifier.create(spanService().countSpansPerWorkspace())
                    .verifyErrorMatches(thrown -> thrown == failure);
        }

        /** Its own test rather than a case of the one above: a different DAO call is a different place to swallow. */
        @Test
        void getSpanBIInformation__whenTheQueryFails__thenTheErrorReachesTheCaller() {
            var failure = new IllegalStateException("TIMEOUT_EXCEEDED");
            when(spanDAO.getSpanBIInformationPerProject()).thenReturn(Flux.error(failure));

            StepVerifier.create(spanService().getSpanBIInformation())
                    .verifyErrorMatches(thrown -> thrown == failure);
        }

        @Test
        void getSpanBreakdownPerWorkspace__whenTheQueryFails__thenTheErrorReachesTheCaller() {
            var failure = new IllegalStateException("TIMEOUT_EXCEEDED");
            when(spanDAO.countSpansBreakdownPerWorkspace()).thenReturn(Flux.error(failure));

            StepVerifier.create(spanService().getSpanBreakdownPerWorkspace())
                    .verifyErrorMatches(thrown -> thrown == failure);
        }

        /**
         * The other half of a collection: the demo lookup the exclusion depends on, which the query tests never
         * reach because they fail before it. One collection covers it, since all three reach the lookup through the
         * same helper.
         */
        @Test
        void countSpansPerWorkspace__whenTheDemoLookupFails__thenTheErrorReachesTheCaller() {
            var failure = new IllegalStateException("demo project lookup unavailable");
            when(spanDAO.countSpansPerWorkspaceProject()).thenReturn(
                    Flux.just(workspaceProjectCount(WORKSPACE_ID, REGULAR_PROJECT_ID, randomCount())));
            when(projectService.getDemoProjectIdsInWorkspaces(Set.of(WORKSPACE_ID)))
                    .thenReturn(Mono.error(failure));

            StepVerifier.create(spanService().countSpansPerWorkspace())
                    .verifyErrorMatches(thrown -> thrown == failure);
        }

        private SpanService spanService() {
            return newSpanService(DatabaseAnalyticsDataModelConfig.builder().build());
        }

        private WorkspaceProjectCount workspaceProjectCount(String workspaceId, UUID projectId, long count) {
            return WorkspaceProjectCount.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .count(count)
                    .build();
        }

        private long randomCount() {
            return RandomUtils.secure().randomLong(1, 1_000);
        }
    }
}
