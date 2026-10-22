package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.UsageProjectsResponse.WorkspaceProjectName;
import com.comet.opik.api.sorting.SortingFactoryProjects;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import com.comet.opik.podam.PodamFactoryUtils;
import jakarta.inject.Provider;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProjectServiceImplTest {

    private static final int DEMO_PROJECT_WORKSPACE_CHUNK_SIZE = 1_000;
    private static final int ID_LOOKUP_CHUNK_SIZE = 1_000;
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

        /**
         * Asserts the contract rather than the partitioning: every workspace is offered to the lookup exactly once,
         * no single query exceeds the bound, and the union of what the queries matched comes back. Deriving the
         * expected batches with {@code Lists.partition} would restate the implementation, so a change to how the
         * work is split would move test and production together and prove nothing.
         */
        @Test
        void getDemoProjectIdsInWorkspaces__whenWorkspacesExceedTheChunkSize__thenEachIsLookedUpOnceWithinTheBound() {
            var workspaceIds = Stream.generate(() -> UUID.randomUUID().toString())
                    .limit(DEMO_PROJECT_WORKSPACE_CHUNK_SIZE + 1)
                    .collect(Collectors.toUnmodifiableSet());
            var queriedBatches = new ArrayList<Set<String>>();
            var matchedIds = new ArrayList<UUID>();

            stubTransaction();
            // One demo project per batch, so a lookup that dropped a batch's result comes back short
            when(projectDAO.findByGlobalNames(eq(DemoData.PROJECTS), anySet())).thenAnswer(invocation -> {
                queriedBatches.add(invocation.getArgument(1));
                var demoProject = factory.manufacturePojo(Project.class).toBuilder()
                        .id(ID_GENERATOR.generateId())
                        .name(DemoData.PROJECTS.getFirst())
                        .build();
                matchedIds.add(demoProject.id());
                return List.of(demoProject);
            });

            var actualIds = projectService.getDemoProjectIdsInWorkspaces(workspaceIds).block();

            assertThat(queriedBatches)
                    .as("the workspaces did not fit in one query, so the bound is exercised")
                    .hasSizeGreaterThan(1)
                    .allSatisfy(batch -> assertThat(batch)
                            .hasSizeLessThanOrEqualTo(DEMO_PROJECT_WORKSPACE_CHUNK_SIZE));
            assertThat(queriedBatches.stream().flatMap(Set::stream).toList())
                    .as("every workspace is looked up exactly once")
                    .containsExactlyInAnyOrderElementsOf(workspaceIds);
            assertThat(actualIds)
                    .as("the union of what every batch matched")
                    .containsExactlyInAnyOrderElementsOf(matchedIds);
        }

        @Test
        void getDemoProjectIdsInWorkspaces__whenNoWorkspaceHasADemoProject__thenReturnsEmpty() {
            var workspaceIds = Set.of(UUID.randomUUID().toString());

            stubTransaction();
            when(projectDAO.findByGlobalNames(DemoData.PROJECTS, workspaceIds)).thenReturn(List.of());

            var actualIds = projectService.getDemoProjectIdsInWorkspaces(workspaceIds).block();

            assertThat(actualIds).isEmpty();
        }

        /** Emptiness is handled with the null-safe {@code CollectionUtils.isEmpty}, so null takes the same path. */
        @ParameterizedTest
        @NullAndEmptySource
        void getDemoProjectIdsInWorkspaces__whenNoWorkspaces__thenReturnsEmptyWithoutTouchingTheDatabase(
                Set<String> workspaceIds) {
            var actualIds = projectService.getDemoProjectIdsInWorkspaces(workspaceIds).block();

            assertThat(actualIds).isEmpty();
            verifyNoInteractions(template);
        }

        @Test
        void findNamesByIdsAcrossWorkspaces__whenIdsExceedTheChunkSize__thenEveryChunkIsMerged() {
            var ids = Stream.generate(ID_GENERATOR::generateId)
                    .limit(ID_LOOKUP_CHUNK_SIZE + 1)
                    .collect(Collectors.toUnmodifiableSet());
            var queriedBatches = new ArrayList<Collection<UUID>>();

            stubTransaction();
            when(projectDAO.findNamesByIds(anyCollection())).thenAnswer(invocation -> {
                Collection<UUID> batch = invocation.getArgument(0);
                queriedBatches.add(batch);
                return batch.stream()
                        .map(id -> new WorkspaceProjectName("ws-" + id, id, "name-" + id))
                        .toList();
            });

            var actualNames = projectService.findNamesByIdsAcrossWorkspaces(ids).block();

            assertThat(queriedBatches)
                    .hasSizeGreaterThan(1)
                    .allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(ID_LOOKUP_CHUNK_SIZE));
            assertThat(queriedBatches.stream().flatMap(Collection::stream).toList())
                    .containsExactlyInAnyOrderElementsOf(ids);
            assertThat(actualNames).hasSize(ids.size())
                    .allSatisfy((id, name) -> assertThat(name).isEqualTo("name-" + id));
        }

        @ParameterizedTest
        @NullAndEmptySource
        void findNamesByIdsAcrossWorkspaces__whenNoIds__thenReturnsEmptyWithoutTouchingTheDatabase(Set<UUID> ids) {
            var actualNames = projectService.findNamesByIdsAcrossWorkspaces(ids).block();

            assertThat(actualNames).isEmpty();
            verifyNoInteractions(template);
        }

        private void stubTransaction() {
            when(template.inTransaction(eq(READ_ONLY), any())).thenAnswer(invocation -> {
                TxAction<?> callback = invocation.getArgument(1);
                return callback.execute(handle);
            });
            when(handle.attach(ProjectDAO.class)).thenReturn(projectDAO);
        }
    }

    @Nested
    class GroupByName {

        /**
         * Callers look up with {@code WorkspaceUtils.getProjectName}, which strips, while MySQL's PAD SPACE collation
         * still returns a project stored with trailing spaces. Keying by the raw name made the lookup miss and the
         * feedback-score batch fail with an NPE on {@code project.id()}.
         */
        @Test
        void groupByName__whenStoredNameHasSurroundingWhitespace__thenFoundByStrippedName() {
            var project = factory.manufacturePojo(Project.class).toBuilder()
                    .name("  Padded Project  ")
                    .build();

            var projectsByName = ProjectService.groupByName(List.of(project));

            assertThat(projectsByName.get("Padded Project")).isEqualTo(project);
            assertThat(projectsByName.get("padded project")).isEqualTo(project);
        }
    }
}
