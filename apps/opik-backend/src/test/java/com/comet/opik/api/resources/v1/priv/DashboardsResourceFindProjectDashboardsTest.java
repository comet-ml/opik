package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dashboard;
import com.comet.opik.api.Dashboard.DashboardPage;
import com.comet.opik.api.DashboardScope;
import com.comet.opik.api.DashboardType;
import com.comet.opik.api.filter.DashboardField;
import com.comet.opik.api.filter.DashboardFilter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.DashboardResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Find project dashboards")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DashboardsResourceFindProjectDashboardsTest {

    public static final String[] DASHBOARD_IGNORED_FIELDS = {"id", "workspaceId", "slug", "projectName", "createdAt",
            "lastUpdatedAt", "createdBy", "lastUpdatedBy"};

    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private final TestContainersSetup setup = new TestContainersSetup();

    @RegisterApp
    private final TestDropwizardAppExtension APP = setup.APP;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private DashboardResourceClient dashboardResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);

        this.dashboardResourceClient = new DashboardResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, podamFactory);
    }

    @AfterAll
    void tearDownAll() {
        setup.wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(setup.wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private void assertDashboardPage(DashboardPage page, int expectedPage, int expectedSize,
            List<Dashboard> expectedDashboards) {
        assertThat(page).isNotNull();
        assertThat(page.page()).isEqualTo(expectedPage);
        assertThat(page.size()).isEqualTo(expectedSize);
        assertThat(page.content()).hasSize(expectedSize);

        assertThat(page.content())
                .usingRecursiveComparison()
                .isEqualTo(expectedDashboards);
    }

    @Test
    @DisplayName("Find project dashboards with default parameters")
    void findProjectDashboardsWithDefaultParameters() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var dashboard1 = dashboardResourceClient.createPartialDashboard().projectId(projectId).build();
        var dashboard2 = dashboardResourceClient.createPartialDashboard().projectId(projectId).build();

        var id1 = dashboardResourceClient.create(dashboard1, apiKey, workspaceName);
        var id2 = dashboardResourceClient.create(dashboard2, apiKey, workspaceName);

        dashboard1 = dashboardResourceClient.get(id1, apiKey, workspaceName, HttpStatus.SC_OK);
        dashboard2 = dashboardResourceClient.get(id2, apiKey, workspaceName, HttpStatus.SC_OK);

        var page = dashboardResourceClient.getProjectDashboards(projectId, apiKey, workspaceName, 1, 10, null, null,
                null);

        assertDashboardPage(page, 1, 2, List.of(dashboard2, dashboard1));
    }

    @Test
    @DisplayName("Find project dashboards by name search")
    void findProjectDashboardsByNameSearch() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var uniqueName = "UniqueSearchName-" + UUID.randomUUID();
        var dashboard1 = dashboardResourceClient.createPartialDashboard()
                .name(uniqueName)
                .projectId(projectId)
                .build();
        var dashboard2 = dashboardResourceClient.createPartialDashboard()
                .name("Other Dashboard")
                .projectId(projectId)
                .build();

        var id1 = dashboardResourceClient.create(dashboard1, apiKey, workspaceName);
        dashboardResourceClient.create(dashboard2, apiKey, workspaceName);

        dashboard1 = dashboardResourceClient.get(id1, apiKey, workspaceName, HttpStatus.SC_OK);

        var page = dashboardResourceClient.getProjectDashboards(projectId, apiKey, workspaceName, 1, 10,
                "UniqueSearch", null, null);

        assertDashboardPage(page, 1, 1, List.of(dashboard1));
    }

    @Test
    @DisplayName("Find project dashboards with pagination")
    void findProjectDashboardsWithPagination() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var dashboards = new ArrayList<Dashboard>();
        for (int i = 0; i < 5; i++) {
            var dashboard = dashboardResourceClient.createPartialDashboard()
                    .name("Pagination Test " + i)
                    .projectId(projectId)
                    .build();
            var id = dashboardResourceClient.create(dashboard, apiKey, workspaceName);
            dashboards.add(dashboardResourceClient.get(id, apiKey, workspaceName, HttpStatus.SC_OK));
        }

        var page1 = dashboardResourceClient.getProjectDashboards(projectId, apiKey, workspaceName, 1, 2, null, null,
                null);
        var page2 = dashboardResourceClient.getProjectDashboards(projectId, apiKey, workspaceName, 2, 2, null, null,
                null);

        assertDashboardPage(page1, 1, 2, dashboards.reversed().subList(0, 2));
        assertDashboardPage(page2, 2, 2, dashboards.reversed().subList(2, 4));
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("Find project dashboards with filters")
    void findProjectDashboardsWithFilters(
            Function<List<Dashboard>, List<DashboardFilter>> getFilters,
            Function<List<Dashboard>, List<Dashboard>> getExpected) {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var dashboards = List.of(
                dashboardResourceClient.createPartialDashboard()
                        .type(DashboardType.MULTI_PROJECT)
                        .projectId(projectId)
                        .build(),
                dashboardResourceClient.createPartialDashboard()
                        .type(DashboardType.EXPERIMENTS)
                        .projectId(projectId)
                        .build());

        var created = dashboards.stream()
                .map(d -> {
                    var id = dashboardResourceClient.create(d, apiKey, workspaceName);
                    return dashboardResourceClient.get(id, apiKey, workspaceName, HttpStatus.SC_OK);
                })
                .toList();

        var filters = getFilters.apply(created);
        var expected = getExpected.apply(created);

        var page = dashboardResourceClient.getProjectDashboards(projectId, apiKey, workspaceName, 1, 10, null, null,
                filters);

        assertDashboardPage(page, 1, expected.size(), expected);
    }

    static Stream<Arguments> findProjectDashboardsWithFilters() {
        return Stream.of(
                // Filter by type MULTI_PROJECT
                Arguments.of(
                        (Function<List<Dashboard>, List<DashboardFilter>>) dashboards -> List.of(
                                DashboardFilter.builder()
                                        .field(DashboardField.TYPE)
                                        .operator(Operator.EQUAL)
                                        .value(DashboardType.MULTI_PROJECT.getValue())
                                        .build()),
                        (Function<List<Dashboard>, List<Dashboard>>) dashboards -> List.of(
                                dashboards.get(0))),
                // Filter by type EXPERIMENTS
                Arguments.of(
                        (Function<List<Dashboard>, List<DashboardFilter>>) dashboards -> List.of(
                                DashboardFilter.builder()
                                        .field(DashboardField.TYPE)
                                        .operator(Operator.EQUAL)
                                        .value(DashboardType.EXPERIMENTS.getValue())
                                        .build()),
                        (Function<List<Dashboard>, List<Dashboard>>) dashboards -> List.of(
                                dashboards.get(1))),
                // Filter by scope WORKSPACE (all project dashboards have WORKSPACE scope)
                Arguments.of(
                        (Function<List<Dashboard>, List<DashboardFilter>>) dashboards -> List.of(
                                DashboardFilter.builder()
                                        .field(DashboardField.SCOPE)
                                        .operator(Operator.EQUAL)
                                        .value(DashboardScope.WORKSPACE.getValue())
                                        .build()),
                        (Function<List<Dashboard>, List<Dashboard>>) dashboards -> List.of(
                                dashboards.get(1), dashboards.get(0))));
    }

    @Test
    @DisplayName("Find project dashboards no filters returns all")
    void findProjectDashboardsNoFiltersReturnsAll() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var dashboard1 = dashboardResourceClient.createPartialDashboard()
                .type(DashboardType.MULTI_PROJECT)
                .scope(DashboardScope.WORKSPACE)
                .projectId(projectId)
                .build();
        var dashboard2 = dashboardResourceClient.createPartialDashboard()
                .type(DashboardType.EXPERIMENTS)
                .scope(DashboardScope.WORKSPACE)
                .projectId(projectId)
                .build();

        dashboardResourceClient.create(dashboard1, apiKey, workspaceName);
        dashboardResourceClient.create(dashboard2, apiKey, workspaceName);

        var page = dashboardResourceClient.getProjectDashboards(projectId, apiKey, workspaceName, 1, 10, null, null,
                null);

        assertThat(page.content()).hasSize(2);
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("Find project dashboards with empty result")
    void findProjectDashboardsWithEmptyResult() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var page = dashboardResourceClient.getProjectDashboards(projectId, apiKey, workspaceName, 1, 10,
                "NonExistentSearch", null, null);

        assertDashboardPage(page, 1, 0, List.of());
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("Sort project dashboards by valid fields")
    void findProjectDashboards__whenSortingByValidFields__thenReturnDashboardsSorted(
            Comparator<Dashboard> comparator, SortingField sorting) {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        List<Dashboard> expectedDashboards = IntStream.range(0, 5)
                .mapToObj(i -> dashboardResourceClient.createPartialDashboard()
                        .name("Dashboard " + (char) ('A' + i))
                        .projectId(projectId)
                        .build())
                .map(dashboard -> {
                    var id = dashboardResourceClient.create(dashboard, apiKey, workspaceName);
                    return dashboardResourceClient.get(id, apiKey, workspaceName, HttpStatus.SC_OK);
                })
                .sorted(comparator)
                .toList();

        var page = dashboardResourceClient.getProjectDashboards(projectId, apiKey, workspaceName, 1, 10, null,
                List.of(sorting), null);

        assertThat(page.sortableBy()).isNotEmpty();
        assertThat(page.content()).hasSize(5);
        assertThat(page.content())
                .extracting(Dashboard::id)
                .containsExactlyElementsOf(expectedDashboards.stream().map(Dashboard::id).toList());
    }

    static Stream<Arguments> findProjectDashboards__whenSortingByValidFields__thenReturnDashboardsSorted() {
        Comparator<Dashboard> idComparator = Comparator.comparing(Dashboard::id);
        Comparator<Dashboard> nameComparator = Comparator.comparing(Dashboard::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<Dashboard> createdAtComparator = Comparator.comparing(Dashboard::createdAt);
        Comparator<Dashboard> lastUpdatedAtComparator = Comparator.comparing(Dashboard::lastUpdatedAt);

        return Stream.of(
                Arguments.of(idComparator,
                        SortingField.builder().field("id").direction(Direction.ASC).build()),
                Arguments.of(idComparator.reversed(),
                        SortingField.builder().field("id").direction(Direction.DESC).build()),
                Arguments.of(nameComparator,
                        SortingField.builder().field("name").direction(Direction.ASC).build()),
                Arguments.of(nameComparator.reversed(),
                        SortingField.builder().field("name").direction(Direction.DESC).build()),
                Arguments.of(createdAtComparator,
                        SortingField.builder().field("created_at").direction(Direction.ASC).build()),
                Arguments.of(createdAtComparator.reversed(),
                        SortingField.builder().field("created_at").direction(Direction.DESC).build()),
                Arguments.of(lastUpdatedAtComparator,
                        SortingField.builder().field("last_updated_at").direction(Direction.ASC).build()),
                Arguments.of(lastUpdatedAtComparator.reversed(),
                        SortingField.builder().field("last_updated_at").direction(Direction.DESC).build()));
    }
}
