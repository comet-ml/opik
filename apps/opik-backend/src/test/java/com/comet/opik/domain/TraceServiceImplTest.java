package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import com.comet.opik.api.error.InvalidUUIDException;
import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.domain.attachment.AttachmentReinjectorService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.attachment.AttachmentStripperService;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.ErrorUtils;
import com.google.common.eventbus.EventBus;
import io.r2dbc.spi.Connection;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamFactoryImpl;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.ProjectService.DEFAULT_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceServiceImplTest {

    public static final LockService DUMMY_LOCK_SERVICE = new DummyLockService();

    private TraceServiceImpl traceService;

    @Mock
    private TraceDAO traceDao;

    @Mock
    private DeletionEventDAO deletionEventDAO;

    @Mock
    private TransactionTemplateAsync template;

    @Mock
    private ProjectService projectService;

    @Mock
    private EventBus eventBus;

    @Mock
    private AttachmentStripperService attachmentStripperService;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private AttachmentReinjectorService attachmentReinjectorService;

    private final DatabaseAnalyticsDataModelConfig databaseAnalyticsDataModelConfig = DatabaseAnalyticsDataModelConfig
            .builder()
            .build();

    private final PodamFactory factory = new PodamFactoryImpl();
    private final TraceSortingFactory traceSortingFactory = new TraceSortingFactory();
    private final IdGenerator idGenerator = TestIdGeneratorFactory.create();

    @BeforeEach
    void setUp() {
        // Mock AttachmentStripperService to return JsonNode unchanged (no stripping for tests)
        lenient().when(attachmentStripperService.stripAttachments(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0)); // Return first argument (JsonNode) unchanged

        traceService = newTraceService(databaseAnalyticsDataModelConfig);
    }

    private TraceServiceImpl newTraceService(DatabaseAnalyticsDataModelConfig databaseAnalyticsDataModelConfig) {
        var opikConfiguration = new OpikConfiguration();
        opikConfiguration.setDatabaseAnalyticsDataModel(databaseAnalyticsDataModelConfig);
        return new TraceServiceImpl(
                traceDao,
                deletionEventDAO,
                template,
                projectService,
                idGenerator,
                DUMMY_LOCK_SERVICE,
                eventBus,
                traceSortingFactory,
                attachmentStripperService,
                attachmentService,
                attachmentReinjectorService,
                opikConfiguration);
    }

    @Nested
    @DisplayName("Create Traces:")
    class CreateTrace {

        @Test
        @DisplayName("when creating traces with uuid version not 7, then return invalid uuid version exception")
        void create__whenCreatingTracesWithUUIDVersionNot7__thenThrowException() {

            // given
            var projectName = "projectName";
            var traceId = UUID.randomUUID();

            // then
            assertThrows(InvalidUUIDException.class, () -> traceService.create(Trace.builder()
                    .id(traceId)
                    .projectName(projectName)
                    .startTime(Instant.now())
                    .build())
                    .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, DEFAULT_USER)
                            .put(RequestContext.WORKSPACE_ID, UUID.randomUUID().toString()))
                    .block());
        }

    }

    @Nested
    @DisplayName("Find Traces:")
    class FindTraces {

        @Test
        @DisplayName("when project name is not found, then return empty page")
        void find__whenProjectNameIsNotFound__thenReturnNotFoundException() {

            // given
            var projectName = "projectName";
            int page = 1;
            int size = 10;
            String workspaceId = UUID.randomUUID().toString();

            when(projectService.resolveProjectIdAndVerifyVisibility(null, projectName))
                    .thenReturn(Mono.error(ErrorUtils.failWithNotFoundName("Project", projectName)));

            Exception exception = assertThrows(NotFoundException.class, () -> traceService
                    .find(page, size, TraceSearchCriteria.builder()
                            .projectName(projectName)
                            .build())
                    .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, DEFAULT_USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .block());

            assertThat(exception.getMessage()).isEqualTo("Project name: %s not found".formatted(projectName));
        }

        @Test
        @DisplayName("when project id not empty, then return search by project id")
        void find__whenProjectIdNotEmpty__thenReturnSearchByProjectId() {

            // given
            UUID projectId = UUID.randomUUID();
            int page = 1;
            int size = 10;
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(projectId)
                    .build();
            Connection connection = mock(Connection.class);

            // when
            when(traceDao.find(anyInt(), anyInt(),
                    eq(TraceSearchCriteria.builder().projectId(projectId).build()),
                    any()))
                    .thenReturn(Mono.just(
                            new Trace.TracePage(1, 1, 1, List.of(trace), traceSortingFactory.getSortableFields())));

            when(template.nonTransaction(any()))
                    .thenAnswer(invocation -> {
                        TransactionTemplateAsync.TransactionCallback<Trace.TracePage> callback = invocation
                                .getArgument(0);

                        return callback.execute(connection);
                    });

            when(projectService.resolveProjectIdAndVerifyVisibility(projectId, null))
                    .thenReturn(Mono.just(projectId));

            // Mock AttachmentReinjectorService to return trace unchanged (no reinjection needed for this test)
            when(attachmentReinjectorService.reinjectAttachments(any(Trace.class), any(Boolean.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            var actualResult = traceService
                    .find(page, size, TraceSearchCriteria.builder().projectId(projectId).build())
                    .block();

            // then
            Assertions.assertNotNull(actualResult);
            assertThat(actualResult.page()).isEqualTo(1);
            assertThat(actualResult.size()).isEqualTo(1);
            assertThat(actualResult.total()).isEqualTo(1);
            assertThat(actualResult.content()).contains(trace);
        }
    }

    @Nested
    @DisplayName("Delete Traces:")
    class DeleteTraces {

        @Test
        @DisplayName("when capture is disabled, then delete records no deletion events")
        void delete__whenCaptureDisabled__thenRecordsNoDeletionEvents() {
            var ids = Set.of(idGenerator.generateId(), idGenerator.generateId());
            var projectId = idGenerator.generateId();
            var workspaceId = UUID.randomUUID().toString();
            var connection = mockDeleteFlow();
            when(traceDao.delete(ids, projectId, connection)).thenReturn(Mono.empty());

            // traceService is built with capture disabled (default config)
            assertDoesNotThrow(() -> traceService
                    .delete(ids, projectId)
                    .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, DEFAULT_USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .block());

            verify(traceDao).delete(ids, projectId, connection);
            verifyTracesDeletedPosted(ids, projectId, workspaceId);
            verifyNoInteractions(deletionEventDAO);
        }

        @Test
        @DisplayName("when capture is enabled and recording fails, then the delete still succeeds")
        void delete__whenCaptureEnabledAndRecordingFails__thenDeleteSucceeds() {
            var ids = Set.of(idGenerator.generateId(), idGenerator.generateId());
            var projectId = idGenerator.generateId();
            var workspaceId = UUID.randomUUID().toString();
            var connection = mockDeleteFlow();
            when(traceDao.delete(ids, projectId, connection)).thenReturn(Mono.empty());
            when(deletionEventDAO.insert(any(), eq(DEFAULT_USER)))
                    .thenReturn(Mono.error(new RuntimeException("Error inserting deletion events")));

            var traceService = newTraceService(DatabaseAnalyticsDataModelConfig.builder()
                    .traceDeletionEventsCaptureEnabled(true)
                    .build());
            // Capture is best-effort: its failure is swallowed and must not fail the deletion.
            assertDoesNotThrow(() -> traceService
                    .delete(ids, projectId)
                    .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, DEFAULT_USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .block());

            verify(traceDao).delete(ids, projectId, connection);
            verifyTracesDeletedPosted(ids, projectId, workspaceId);
            verify(deletionEventDAO).insert(any(), eq(DEFAULT_USER));
        }

        @Test
        @DisplayName("when the delete fails, then no deletion events are recorded and the error propagates")
        void delete__whenDeleteFails__thenRecordsNoDeletionEventsAndPropagates() {
            var ids = Set.of(idGenerator.generateId(), idGenerator.generateId());
            var projectId = idGenerator.generateId();
            var workspaceId = UUID.randomUUID().toString();
            var connection = mockDeleteFlow();
            when(traceDao.delete(ids, projectId, connection))
                    .thenReturn(Mono.error(new RuntimeException("Error deleting traces")));

            var traceService = newTraceService(DatabaseAnalyticsDataModelConfig.builder()
                    .traceDeletionEventsCaptureEnabled(true)
                    .build());
            // Capture runs only after a successful delete, so a failed delete records nothing and surfaces the error.
            assertThatThrownBy(() -> traceService
                    .delete(ids, projectId)
                    .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, DEFAULT_USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .block())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Error deleting traces");

            verify(traceDao).delete(ids, projectId, connection);
            verifyNoInteractions(deletionEventDAO, eventBus);
        }

        private Connection mockDeleteFlow() {
            var connection = mock(Connection.class);
            when(template.nonTransaction(any()))
                    .thenAnswer(invocation -> {
                        TransactionTemplateAsync.TransactionCallback<Void> callback = invocation.getArgument(0);
                        return callback.execute(connection);
                    });
            return connection;
        }

        private void verifyTracesDeletedPosted(Set<UUID> ids, UUID projectId, String workspaceId) {
            var eventCaptor = ArgumentCaptor.forClass(TracesDeleted.class);
            verify(eventBus).post(eventCaptor.capture());
            var event = eventCaptor.getValue();
            assertThat(event.traceIds()).isEqualTo(ids);
            assertThat(event.projectId()).isEqualTo(projectId);
            assertThat(event.workspaceId()).isEqualTo(workspaceId);
            assertThat(event.userName()).isEqualTo(DEFAULT_USER);
        }
    }
}
