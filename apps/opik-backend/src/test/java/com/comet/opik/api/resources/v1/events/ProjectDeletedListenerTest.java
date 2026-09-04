package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ProjectsDeleted;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.auth.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectDeletedListenerTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER_NAME = "admin";

    private ProjectDeletedListener listener;

    @Mock
    private TraceService traceService;

    @BeforeEach
    void setUp() {
        listener = new ProjectDeletedListener(traceService);
    }

    @Test
    @DisplayName("when projects deleted, then deletes traces once per project id")
    void onProjectsDeleted__whenProjectsDeleted__thenCallsDeleteByProjectIdOncePerProject() {
        var projectA = UUID.randomUUID();
        var projectB = UUID.randomUUID();
        when(traceService.deleteByProjectId(any())).thenReturn(Mono.empty());

        listener.onProjectsDeleted(new ProjectsDeleted(Set.of(projectA, projectB), WORKSPACE_ID, USER_NAME));

        verify(traceService).deleteByProjectId(projectA);
        verify(traceService).deleteByProjectId(projectB);
    }

    @Test
    @DisplayName("when projects deleted, then the downstream reactor context carries workspace id and user name")
    void onProjectsDeleted__whenProjectsDeleted__thenContextCarriesWorkspaceIdAndUserName() {
        var projectId = UUID.randomUUID();
        List<String> workspaceIds = new ArrayList<>();
        List<String> userNames = new ArrayList<>();
        when(traceService.deleteByProjectId(any())).thenAnswer(invocation -> Mono.deferContextual(ctx -> {
            workspaceIds.add(ctx.get(RequestContext.WORKSPACE_ID));
            userNames.add(ctx.get(RequestContext.USER_NAME));
            return Mono.empty();
        }));

        listener.onProjectsDeleted(new ProjectsDeleted(Set.of(projectId), WORKSPACE_ID, USER_NAME));

        assertThat(workspaceIds).containsExactly(WORKSPACE_ID);
        assertThat(userNames).containsExactly(USER_NAME);
    }

    @Test
    @DisplayName("when deletion fails for one project, then the error is swallowed and remaining projects are processed")
    void onProjectsDeleted__whenOneProjectDeletionFails__thenErrorSwallowedAndRemainingProjectsProcessed() {
        var failingProject = UUID.randomUUID();
        var succeedingProject = UUID.randomUUID();
        when(traceService.deleteByProjectId(failingProject))
                .thenReturn(Mono.error(new RuntimeException("Error deleting traces")));
        when(traceService.deleteByProjectId(succeedingProject)).thenReturn(Mono.empty());

        assertThatCode(() -> listener.onProjectsDeleted(
                new ProjectsDeleted(Set.of(failingProject, succeedingProject), WORKSPACE_ID, USER_NAME)))
                .doesNotThrowAnyException();

        verify(traceService).deleteByProjectId(failingProject);
        verify(traceService).deleteByProjectId(succeedingProject);
    }
}
