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

import static com.comet.opik.domain.ProjectService.DEFAULT_USER;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
            when(deletionEventDAO.insert(any(), eq(DEFAULT_USER)))
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
            verify(deletionEventDAO).insert(any(), eq(DEFAULT_USER));
        }

        @Test
        @DisplayName("when the delete fails, then no deletion events are recorded and the error propagates")
        void delete__whenDeleteFails__thenRecordsNoDeletionEventsAndPropagates() {
            var traceIds = Set.of(idGenerator.generateId());
            var spanIds = Set.of(idGenerator.generateId(), idGenerator.generateId());
            var projectId = idGenerator.generateId();
            var workspaceId = UUID.randomUUID().toString();
            mockSpanDeleteFlow(traceIds, spanIds, projectId);
            when(spanDAO.deleteByIds(spanIds, projectId))
                    .thenReturn(Mono.error(new RuntimeException("Error deleting spans")));

            spanService = newSpanService(DatabaseAnalyticsDataModelConfig.builder()
                    .spanDeletionEventsCaptureEnabled(true)
                    .build());
            // Capture runs only after a successful delete, so a failed delete records nothing and surfaces the error.
            assertThatThrownBy(() -> spanService
                    .deleteByTraceIds(traceIds, projectId)
                    .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, DEFAULT_USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .block())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Error deleting spans");

            verify(spanDAO).deleteByIds(spanIds, projectId);
            verifyNoInteractions(deletionEventDAO, eventBus);
        }

        // Stubs the cascade steps that run before the span lightweight delete: id resolution and the
        // comment/feedback-score/attachment deletes. deleteByIds and deletionEventDAO are stubbed per-test.
        private void mockSpanDeleteFlow(Set<UUID> traceIds, Set<UUID> spanIds, UUID projectId) {
            when(spanDAO.getSpanIdsForTraces(traceIds, projectId)).thenReturn(Mono.just(spanIds));
            when(commentService.deleteByEntityIds(any(), eq(spanIds))).thenReturn(Mono.just(0L));
            when(feedbackScoreService.deleteBySpanIds(spanIds, projectId)).thenReturn(Mono.empty());
            when(attachmentService.deleteByEntityIds(any(), eq(spanIds))).thenReturn(Mono.just(0L));
        }
    }
}
