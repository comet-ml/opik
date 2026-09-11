package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.sorting.SortingFactoryProjects;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.common.collect.Lists;
import jakarta.inject.Provider;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProjectServiceImplTest {

    private static final int DEMO_PROJECT_WORKSPACE_CHUNK_SIZE = 1_000;
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private final TransactionTemplate template = mock(TransactionTemplate.class);
    private final Handle handle = mock(Handle.class);
    private final ProjectDAO projectDAO = mock(ProjectDAO.class);
    private final Provider<RequestContext> requestContext = mock(Provider.class);
    private final TraceDAO traceDAO = mock(TraceDAO.class);
    private final SortingFactoryProjects sortingFactory = mock(SortingFactoryProjects.class);
    private final SortingQueryBuilder sortingQueryBuilder = mock(SortingQueryBuilder.class);
    private final AnalyticsService analyticsService = mock(AnalyticsService.class);

    private final ProjectService projectService = new ProjectServiceImpl(template, ID_GENERATOR, requestContext,
            traceDAO, sortingFactory, sortingQueryBuilder, analyticsService);

    /**
     * The bounded demo-project lookup, which replaced fetching every demo project in the installation for the daily
     * usage counts. A day's active workspaces fit in one chunk, so chunking is a bound rather than a loop — but it
     * is still the part with something to get wrong.
     */
    @Nested
    class GetDemoProjectIdsInWorkspaces {

        @Test
        void getDemoProjectIdsInWorkspaces__whenWorkspacesExceedTheChunkSize__thenUnionsWhatEveryChunkMatched() {
            var workspaceIds = Stream.generate(() -> UUID.randomUUID().toString())
                    .limit(DEMO_PROJECT_WORKSPACE_CHUNK_SIZE + 1)
                    .collect(Collectors.toUnmodifiableSet());
            var chunks = Lists.partition(List.copyOf(workspaceIds), DEMO_PROJECT_WORKSPACE_CHUNK_SIZE);
            assertThat(chunks).hasSize(2);

            stubTransaction();
            // One demo project per chunk, so a lookup that dropped a chunk's result — or kept only the last —
            // comes back with fewer ids than there were chunks.
            var expectedIds = chunks.stream()
                    .map(this::stubDemoProjectInChunk)
                    .collect(Collectors.toUnmodifiableSet());

            var actualIds = projectService.getDemoProjectIdsInWorkspaces(workspaceIds).block();

            assertThat(actualIds).isEqualTo(expectedIds);
        }

        @Test
        void getDemoProjectIdsInWorkspaces__whenNoWorkspaceHasADemoProject__thenReturnsEmpty() {
            var workspaceIds = Set.of(UUID.randomUUID().toString());

            stubTransaction();
            when(projectDAO.findByGlobalNames(DemoData.PROJECTS, workspaceIds)).thenReturn(List.of());

            var actualIds = projectService.getDemoProjectIdsInWorkspaces(workspaceIds).block();

            assertThat(actualIds).isEmpty();
        }

        @Test
        void getDemoProjectIdsInWorkspaces__whenNoWorkspaces__thenReturnsEmptyWithoutTouchingTheDatabase() {
            var actualIds = projectService.getDemoProjectIdsInWorkspaces(Set.of()).block();

            assertThat(actualIds).isEmpty();
            verifyNoInteractions(template);
        }

        private void stubTransaction() {
            when(template.inTransaction(eq(READ_ONLY), any())).thenAnswer(invocation -> {
                TxAction<?> callback = invocation.getArgument(1);
                return callback.execute(handle);
            });
            when(handle.attach(ProjectDAO.class)).thenReturn(projectDAO);
        }

        private UUID stubDemoProjectInChunk(List<String> chunk) {
            var demoProject = factory.manufacturePojo(Project.class).toBuilder()
                    .id(ID_GENERATOR.generateId())
                    .name(DemoData.PROJECTS.getFirst())
                    .build();
            when(projectDAO.findByGlobalNames(DemoData.PROJECTS, Set.copyOf(chunk)))
                    .thenReturn(List.of(demoProject));
            return demoProject.id();
        }
    }
}
