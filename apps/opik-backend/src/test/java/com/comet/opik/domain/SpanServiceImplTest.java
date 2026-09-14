package com.comet.opik.domain;

import com.comet.opik.api.events.SpansDeleted;
import com.comet.opik.domain.attachment.AttachmentReinjectorService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.attachment.AttachmentStripperService;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.eventbus.EventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.domain.ProjectService.DEFAULT_USER;
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
}
