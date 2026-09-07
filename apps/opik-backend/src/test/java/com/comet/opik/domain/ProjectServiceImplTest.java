package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.events.ProjectsDeleted;
import com.comet.opik.api.sorting.SortingFactoryProjects;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import com.google.common.eventbus.EventBus;
import jakarta.inject.Provider;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER_NAME = "admin";

    private ProjectServiceImpl projectService;

    @Mock
    private TransactionTemplate template;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private Provider<RequestContext> requestContext;

    @Mock
    private TraceDAO traceDAO;

    @Mock
    private SortingQueryBuilder sortingQueryBuilder;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private EventBus eventBus;

    @Mock
    private Handle handle;

    @Mock
    private ProjectDAO projectDAO;

    @BeforeEach
    void setUp() {
        var sortingFactory = new SortingFactoryProjects();
        projectService = new ProjectServiceImpl(
                template,
                idGenerator,
                requestContext,
                traceDAO,
                sortingFactory,
                sortingQueryBuilder,
                analyticsService,
                eventBus);

        lenient().when(requestContext.get()).thenReturn(RequestContext.builder()
                .workspaceId(WORKSPACE_ID)
                .userName(USER_NAME)
                .build());

        lenient().when(template.inTransaction(any(), any())).thenAnswer(invocation -> {
            TxAction<?> callback = invocation.getArgument(1);
            return callback.execute(handle);
        });
        lenient().when(handle.attach(ProjectDAO.class)).thenReturn(projectDAO);
    }

    @Nested
    @DisplayName("Delete Project:")
    class DeleteProject {

        @Test
        @DisplayName("when project exists, then posts ProjectsDeleted event with the project id")
        void delete__whenProjectExists__thenPostsProjectsDeleted() {
            var projectId = UUID.randomUUID();
            when(projectDAO.fetch(projectId, WORKSPACE_ID))
                    .thenReturn(Optional.of(Project.builder().id(projectId).name("project").build()));

            projectService.delete(projectId);

            var eventCaptor = ArgumentCaptor.forClass(ProjectsDeleted.class);
            verify(eventBus).post(eventCaptor.capture());
            var event = eventCaptor.getValue();
            assertThat(event.projectIds()).containsExactly(projectId);
            assertThat(event.workspaceId()).isEqualTo(WORKSPACE_ID);
            assertThat(event.userName()).isEqualTo(USER_NAME);
        }

        @Test
        @DisplayName("when project does not exist, then no event is posted")
        void delete__whenProjectDoesNotExist__thenNoEvent() {
            var projectId = UUID.randomUUID();
            when(projectDAO.fetch(projectId, WORKSPACE_ID)).thenReturn(Optional.empty());

            projectService.delete(projectId);

            verifyNoInteractions(eventBus);
        }

        @Test
        @DisplayName("when ids not empty, then posts one ProjectsDeleted event with all ids")
        void delete__whenIdsNotEmpty__thenPostsProjectsDeletedWithAllIds() {
            var ids = Set.of(UUID.randomUUID(), UUID.randomUUID());

            projectService.delete(ids);

            var eventCaptor = ArgumentCaptor.forClass(ProjectsDeleted.class);
            verify(eventBus).post(eventCaptor.capture());
            var event = eventCaptor.getValue();
            assertThat(event.projectIds()).containsExactlyInAnyOrderElementsOf(ids);
            assertThat(event.workspaceId()).isEqualTo(WORKSPACE_ID);
            assertThat(event.userName()).isEqualTo(USER_NAME);
        }

        @Test
        @DisplayName("when ids empty, then no event is posted")
        void delete__whenIdsEmpty__thenNoEvent() {
            projectService.delete(Set.of());

            verifyNoInteractions(eventBus);
        }
    }
}
