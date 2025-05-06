package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceSearchCriteria;
import com.comet.opik.api.error.InvalidUUIDVersionException;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.ErrorUtils;
import com.fasterxml.uuid.Generators;
import com.google.common.eventbus.EventBus;
import io.r2dbc.spi.Connection;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamFactoryImpl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.domain.ProjectService.DEFAULT_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceServiceImplTest {

    public static final LockService DUMMY_LOCK_SERVICE = new DummyLockService();

    private TraceServiceImpl traceService;

    @Mock
    private TraceDAO traceDao;

    @Mock
    private SpanService spanService;

    @Mock
    private FeedbackScoreDAO feedbackScoreDAO;

    @Mock
    private CommentDAO commentDAO;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private TransactionTemplateAsync template;

    @Mock
    private ProjectService projectService;

    @Mock
    private EventBus eventBus;

    private final PodamFactory factory = new PodamFactoryImpl();
    private final TraceSortingFactory traceSortingFactory = new TraceSortingFactory();

    @BeforeEach
    void setUp() {
        traceService = new TraceServiceImpl(
                traceDao,
                spanService,
                feedbackScoreDAO,
                commentDAO,
                attachmentService,
                template,
                projectService,
                () -> Generators.timeBasedEpochGenerator().generate(),
                DUMMY_LOCK_SERVICE,
                eventBus);
    }

    @Nested
    @DisplayName("Create Traces:")
    class CreateTrace {

        @Test
        @DisplayName("when creating traces with uuid version not 7, then return invalid uuid version exception")
        void create__whenCreatingTracesWithUUIDVersionNot7__thenReturnInvalidUUIDVersionException() {

            // given
            var projectName = "projectName";
            var traceId = UUID.randomUUID();

            // then
            assertThrows(InvalidUUIDVersionException.class, () -> traceService.create(Trace.builder()
                    .id(traceId)
                    .projectName(projectName)
                    .startTime(Instant.now())
                    .build())
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
                    .thenThrow(ErrorUtils.failWithNotFoundName("Project", projectName));

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
                    .thenReturn(projectId);

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

}