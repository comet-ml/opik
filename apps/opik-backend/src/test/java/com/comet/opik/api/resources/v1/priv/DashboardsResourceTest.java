package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dashboard;
import com.comet.opik.api.Dashboard.DashboardPage;
import com.comet.opik.api.DashboardUpdate;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DashboardResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class DashboardsResourceTest {

    public static final String[] DASHBOARD_IGNORED_FIELDS = {"id", "workspaceId", "slug", "createdAt", "lastUpdatedAt",
            "createdBy", "lastUpdatedBy"};

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;
    private final TestDropwizardAppExtensionUtils.AppContextConfig contextConfig;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        contextConfig = TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .authCacheTtlInSeconds(null)
                .build();

        APP = newTestDropwizardAppExtension(contextConfig);
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private DashboardResourceClient dashboardResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        this.dashboardResourceClient = new DashboardResourceClient(this.client, baseURI);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE_NAME, WORKSPACE_ID);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Nested
    @DisplayName("Create dashboard")
    class CreateDashboard {

        @Test
        @DisplayName("Create dashboard with all fields")
        void createDashboardWithAllFields() {
            var uniqueName = "Test Dashboard " + UUID.randomUUID();
            var dashboard = dashboardResourceClient.createPartialDashboard()
                    .name(uniqueName)
                    .description("This is a test dashboard")
                    .build();

            var createdDashboard = dashboardResourceClient.createAndGet(dashboard, API_KEY, TEST_WORKSPACE_NAME);

            assertDashboard(dashboard, createdDashboard);
            assertThat(createdDashboard.id()).isNotNull();
            assertThat(createdDashboard.slug()).isNotNull();
            assertThat(createdDashboard.workspaceId()).isEqualTo(WORKSPACE_ID);
            assertThat(createdDashboard.createdBy()).isEqualTo(USER);
            assertThat(createdDashboard.lastUpdatedBy()).isEqualTo(USER);
            assertThat(createdDashboard.lastUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Create dashboard without description")
        void createDashboardWithoutDescription() {
            var dashboard = dashboardResourceClient.createPartialDashboard()
                    .description(null)
                    .build();

            var createdDashboard = dashboardResourceClient.createAndGet(dashboard, API_KEY, TEST_WORKSPACE_NAME);

            assertDashboard(dashboard, createdDashboard);
            assertThat(createdDashboard.description()).isNull();
        }

        @Test
        @DisplayName("Create dashboard with special characters in name")
        void createDashboardWithSpecialCharactersInName() {
            var dashboard = dashboardResourceClient.createPartialDashboard()
                    .name("Test Dashboard: Special ☆ Characters & Symbols")
                    .build();

            var createdDashboard = dashboardResourceClient.createAndGet(dashboard, API_KEY, TEST_WORKSPACE_NAME);

            assertDashboard(dashboard, createdDashboard);
            assertThat(createdDashboard.slug()).isEqualTo("test-dashboard-special-characters-symbols");
        }

        @Test
        @DisplayName("Create dashboard with duplicate name fails")
        void createDashboardWithDuplicateNameFails() {
            var dashboardName = "Duplicate Dashboard " + UUID.randomUUID();
            var dashboard1 = dashboardResourceClient.createPartialDashboard()
                    .name(dashboardName)
                    .build();

            dashboardResourceClient.create(dashboard1, API_KEY, TEST_WORKSPACE_NAME);

            // Try to create another dashboard with the same name
            var dashboard2 = dashboardResourceClient.createPartialDashboard()
                    .name(dashboardName)
                    .build();

            try (var response = dashboardResourceClient.callCreate(dashboard2, API_KEY, TEST_WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
            }
        }

        @Test
        @DisplayName("Create dashboard without name fails")
        void createDashboardWithoutNameFails() {
            var dashboard = dashboardResourceClient.createPartialDashboard()
                    .name("")
                    .build();

            try (var response = dashboardResourceClient.callCreate(dashboard, API_KEY, TEST_WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
            }
        }
    }

    @Nested
    @DisplayName("Get dashboard by id")
    class GetDashboardById {

        @Test
        @DisplayName("Get existing dashboard by id")
        void getExistingDashboardById() {
            var dashboard = dashboardResourceClient.createPartialDashboard().build();
            var id = dashboardResourceClient.create(dashboard, API_KEY, TEST_WORKSPACE_NAME);

            var actualDashboard = dashboardResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);

            assertDashboard(dashboard, actualDashboard);
            assertThat(actualDashboard.id()).isEqualTo(id);
        }

        @Test
        @DisplayName("Get non-existent dashboard returns 404")
        void getNonExistentDashboardReturns404() {
            var nonExistentId = UUID.randomUUID();
            dashboardResourceClient.get(nonExistentId, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Get dashboard from different workspace returns 404")
        void getDashboardFromDifferentWorkspaceReturns404() {
            // Create dashboard in one workspace
            var dashboard = dashboardResourceClient.createPartialDashboard().build();
            var id = dashboardResourceClient.create(dashboard, API_KEY, TEST_WORKSPACE_NAME);

            // Try to get it from a different workspace
            String differentApiKey = UUID.randomUUID().toString();
            String differentWorkspaceName = "different-workspace-" + UUID.randomUUID();
            String differentWorkspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(differentApiKey, differentWorkspaceName, differentWorkspaceId);

            dashboardResourceClient.get(id, differentApiKey, differentWorkspaceName, HttpStatus.SC_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Find dashboards")
    class FindDashboards {

        @Test
        @DisplayName("Find dashboards with default parameters")
        void findDashboardsWithDefaultParameters() {
            // Create isolated workspace for this test
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create multiple dashboards
            var dashboard1 = dashboardResourceClient.createPartialDashboard().build();
            var dashboard2 = dashboardResourceClient.createPartialDashboard().build();

            var id1 = dashboardResourceClient.create(dashboard1, apiKey, workspaceName);
            var id2 = dashboardResourceClient.create(dashboard2, apiKey, workspaceName);

            dashboard1 = dashboardResourceClient.get(id1, apiKey, workspaceName, HttpStatus.SC_OK);
            dashboard2 = dashboardResourceClient.get(id2, apiKey, workspaceName, HttpStatus.SC_OK);

            // Find dashboards
            var page = dashboardResourceClient.find(apiKey, workspaceName, 1, 10, null, HttpStatus.SC_OK);

            // Verify results (newest first due to updated_at DESC ordering)
            assertDashboardPage(page, 1, 2, List.of(dashboard2, dashboard1));
        }

        @Test
        @DisplayName("Find dashboards by name search")
        void findDashboardsByNameSearch() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create dashboards with specific names
            var uniqueName = "UniqueSearchName-" + UUID.randomUUID();
            var dashboard1 = dashboardResourceClient.createPartialDashboard()
                    .name(uniqueName)
                    .build();
            var dashboard2 = dashboardResourceClient.createPartialDashboard()
                    .name("Other Dashboard")
                    .build();

            var id1 = dashboardResourceClient.create(dashboard1, apiKey, workspaceName);
            dashboardResourceClient.create(dashboard2, apiKey, workspaceName);

            dashboard1 = dashboardResourceClient.get(id1, apiKey, workspaceName, HttpStatus.SC_OK);

            // Find by search
            var page = dashboardResourceClient.find(apiKey, workspaceName, 1, 10, "UniqueSearch",
                    HttpStatus.SC_OK);

            assertDashboardPage(page, 1, 1, List.of(dashboard1));
        }

        @Test
        @DisplayName("Find dashboards with pagination")
        void findDashboardsWithPagination() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create 5 dashboards
            var dashboards = new ArrayList<Dashboard>();
            for (int i = 0; i < 5; i++) {
                var dashboard = dashboardResourceClient.createPartialDashboard()
                        .name("Pagination Test " + i)
                        .build();
                var id = dashboardResourceClient.create(dashboard, apiKey, workspaceName);
                dashboards.add(dashboardResourceClient.get(id, apiKey, workspaceName, HttpStatus.SC_OK));
            }

            // Find first page with size 2
            var page1 = dashboardResourceClient.find(apiKey, workspaceName, 1, 2, null, HttpStatus.SC_OK);

            // Find second page with size 2
            var page2 = dashboardResourceClient.find(apiKey, workspaceName, 2, 2, null, HttpStatus.SC_OK);

            // Verify pagination (reversed because of DESC order by updated_at)
            assertDashboardPage(page1, 1, 2, dashboards.reversed().subList(0, 2));
            assertDashboardPage(page2, 2, 2, dashboards.reversed().subList(2, 4));
        }

        @Test
        @DisplayName("Find dashboards with empty result")
        void findDashboardsWithEmptyResult() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Find with non-existent search term
            var page = dashboardResourceClient.find(apiKey, workspaceName, 1, 10, "NonExistentSearch",
                    HttpStatus.SC_OK);

            assertDashboardPage(page, 1, 0, List.of());
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
    }

    @Nested
    @DisplayName("Sort dashboards")
    class SortDashboards {

        @ParameterizedTest
        @MethodSource
        @DisplayName("Sort dashboards by valid fields")
        void findDashboards__whenSortingByValidFields__thenReturnDashboardsSorted(
                Comparator<Dashboard> comparator, String sortingJson) {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dashboard> expectedDashboards = IntStream.range(0, 5)
                    .mapToObj(i -> dashboardResourceClient.createPartialDashboard()
                            .name("Dashboard " + (char) ('A' + i))
                            .build())
                    .map(dashboard -> {
                        var id = dashboardResourceClient.create(dashboard, apiKey, workspaceName);
                        return dashboardResourceClient.get(id, apiKey, workspaceName, HttpStatus.SC_OK);
                    })
                    .sorted(comparator)
                    .toList();

            var page = dashboardResourceClient.find(apiKey, workspaceName, 1, 10, null, sortingJson, HttpStatus.SC_OK);

            assertThat(page.sortableBy()).isNotEmpty();
            assertThat(page.content()).hasSize(5);
            assertThat(page.content())
                    .extracting(Dashboard::id)
                    .containsExactlyElementsOf(expectedDashboards.stream().map(Dashboard::id).toList());
        }

        static Stream<Arguments> findDashboards__whenSortingByValidFields__thenReturnDashboardsSorted() {
            Comparator<Dashboard> idComparator = Comparator.comparing(Dashboard::id);
            Comparator<Dashboard> nameComparator = Comparator.comparing(Dashboard::name, String.CASE_INSENSITIVE_ORDER);
            Comparator<Dashboard> createdAtComparator = Comparator.comparing(Dashboard::createdAt);
            Comparator<Dashboard> lastUpdatedAtComparator = Comparator.comparing(Dashboard::lastUpdatedAt);

            return Stream.of(
                    // ID field sorting
                    Arguments.of(idComparator,
                            "[{\"field\":\"id\",\"direction\":\"ASC\"}]"),
                    Arguments.of(idComparator.reversed(),
                            "[{\"field\":\"id\",\"direction\":\"DESC\"}]"),

                    // NAME field sorting
                    Arguments.of(nameComparator,
                            "[{\"field\":\"name\",\"direction\":\"ASC\"}]"),
                    Arguments.of(nameComparator.reversed(),
                            "[{\"field\":\"name\",\"direction\":\"DESC\"}]"),

                    // CREATED_AT field sorting
                    Arguments.of(createdAtComparator,
                            "[{\"field\":\"created_at\",\"direction\":\"ASC\"}]"),
                    Arguments.of(createdAtComparator.reversed(),
                            "[{\"field\":\"created_at\",\"direction\":\"DESC\"}]"),

                    // LAST_UPDATED_AT field sorting
                    Arguments.of(lastUpdatedAtComparator,
                            "[{\"field\":\"last_updated_at\",\"direction\":\"ASC\"}]"),
                    Arguments.of(lastUpdatedAtComparator.reversed(),
                            "[{\"field\":\"last_updated_at\",\"direction\":\"DESC\"}]"));
        }

        @Test
        @DisplayName("Default sorting without sorting parameter")
        void defaultSortingWithoutSortingParameter() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Dashboard> dashboards = IntStream.range(0, 3)
                    .mapToObj(i -> dashboardResourceClient.createPartialDashboard()
                            .name("Dashboard " + i)
                            .build())
                    .map(dashboard -> {
                        var id = dashboardResourceClient.create(dashboard, apiKey, workspaceName);
                        return dashboardResourceClient.get(id, apiKey, workspaceName, HttpStatus.SC_OK);
                    })
                    .toList();

            var page = dashboardResourceClient.find(apiKey, workspaceName, 1, 10, null, HttpStatus.SC_OK);

            assertThat(page.sortableBy()).isNotEmpty();
            assertThat(page.content()).hasSize(3);
            // Default sorting is by id DESC
            assertThat(page.content().get(0).id()).isEqualTo(dashboards.get(2).id());
            assertThat(page.content().get(1).id()).isEqualTo(dashboards.get(1).id());
            assertThat(page.content().get(2).id()).isEqualTo(dashboards.get(0).id());
        }

        @Test
        @DisplayName("Sortable by fields are returned in response")
        void sortableByFieldsAreReturned() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var page = dashboardResourceClient.find(apiKey, workspaceName, 1, 10, null, HttpStatus.SC_OK);

            assertThat(page.sortableBy()).containsExactlyInAnyOrder(
                    "id", "name", "description", "created_at", "last_updated_at", "created_by", "last_updated_by");
        }
    }

    @Nested
    @DisplayName("Update dashboard")
    class UpdateDashboard {

        @Test
        @DisplayName("Update dashboard name")
        void updateDashboardName() {
            var dashboard = dashboardResourceClient.createPartialDashboard().build();
            var id = dashboardResourceClient.create(dashboard, API_KEY, TEST_WORKSPACE_NAME);
            var createdDashboard = dashboardResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);

            var update = DashboardUpdate.builder()
                    .name("Updated Dashboard Name")
                    .build();

            var updatedDashboard = dashboardResourceClient.updateAndGet(id, update, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(updatedDashboard.name()).isEqualTo("Updated Dashboard Name");
            assertThat(updatedDashboard.slug()).isEqualTo("updated-dashboard-name");
            assertThat(updatedDashboard.lastUpdatedAt()).isAfter(createdDashboard.lastUpdatedAt());
            assertThat(updatedDashboard.lastUpdatedBy()).isEqualTo(USER);
        }

        @Test
        @DisplayName("Update dashboard description")
        void updateDashboardDescription() {
            var dashboard = dashboardResourceClient.createPartialDashboard().build();
            var id = dashboardResourceClient.create(dashboard, API_KEY, TEST_WORKSPACE_NAME);

            var update = DashboardUpdate.builder()
                    .description("Updated description")
                    .build();

            var updatedDashboard = dashboardResourceClient.updateAndGet(id, update, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(updatedDashboard.description()).isEqualTo("Updated description");
            assertThat(updatedDashboard.lastUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Update dashboard config")
        void updateDashboardConfig() {
            var dashboard = dashboardResourceClient.createPartialDashboard().build();
            var id = dashboardResourceClient.create(dashboard, API_KEY, TEST_WORKSPACE_NAME);

            var newConfig = dashboardResourceClient.createValidConfig();
            var update = DashboardUpdate.builder()
                    .config(newConfig)
                    .build();

            var updatedDashboard = dashboardResourceClient.updateAndGet(id, update, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(updatedDashboard.config()).isEqualTo(newConfig);
            assertThat(updatedDashboard.lastUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Update dashboard with duplicate name fails")
        void updateDashboardWithDuplicateNameFails() {
            var dashboard1 = dashboardResourceClient.createPartialDashboard()
                    .name("Dashboard One")
                    .build();
            var dashboard2 = dashboardResourceClient.createPartialDashboard()
                    .name("Dashboard Two")
                    .build();

            dashboardResourceClient.create(dashboard1, API_KEY, TEST_WORKSPACE_NAME);
            var id2 = dashboardResourceClient.create(dashboard2, API_KEY, TEST_WORKSPACE_NAME);

            // Try to update dashboard2 to have the same name as dashboard1
            var update = DashboardUpdate.builder()
                    .name("Dashboard One")
                    .build();

            try (var response = dashboardResourceClient.callUpdate(id2, update, API_KEY, TEST_WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
            }
        }

        @Test
        @DisplayName("Update non-existent dashboard returns 404")
        void updateNonExistentDashboardReturns404() {
            var nonExistentId = UUID.randomUUID();
            var update = DashboardUpdate.builder()
                    .name("Update Non-existent")
                    .build();

            dashboardResourceClient.update(nonExistentId, update, API_KEY, TEST_WORKSPACE_NAME,
                    HttpStatus.SC_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Delete dashboard")
    class DeleteDashboard {

        @Test
        @DisplayName("Delete existing dashboard")
        void deleteExistingDashboard() {
            var dashboard = dashboardResourceClient.createPartialDashboard().build();
            var id = dashboardResourceClient.create(dashboard, API_KEY, TEST_WORKSPACE_NAME);

            // Verify dashboard exists
            dashboardResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);

            // Delete dashboard
            dashboardResourceClient.delete(id, API_KEY, TEST_WORKSPACE_NAME);

            // Verify dashboard is deleted
            dashboardResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Delete non-existent dashboard returns 204")
        void deleteNonExistentDashboardReturns204() {
            var nonExistentId = UUID.randomUUID();
            dashboardResourceClient.delete(nonExistentId, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NO_CONTENT);
        }

        @Test
        @DisplayName("Delete dashboard from different workspace returns 204")
        void deleteDashboardFromDifferentWorkspaceReturns204() {
            // Create dashboard in one workspace
            var dashboard = dashboardResourceClient.createPartialDashboard().build();
            var id = dashboardResourceClient.create(dashboard, API_KEY, TEST_WORKSPACE_NAME);

            // Try to delete from a different workspace
            String differentApiKey = UUID.randomUUID().toString();
            String differentWorkspaceName = "different-workspace-" + UUID.randomUUID();
            String differentWorkspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(differentApiKey, differentWorkspaceName, differentWorkspaceId);

            dashboardResourceClient.delete(id, differentApiKey, differentWorkspaceName, HttpStatus.SC_NO_CONTENT);

            // Verify original dashboard still exists
            dashboardResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
        }
    }

    @Nested
    @DisplayName("Batch delete dashboards")
    class BatchDeleteDashboards {

        @Test
        @DisplayName("Batch delete multiple existing dashboards")
        void batchDeleteMultipleExistingDashboards() {
            var dashboard1 = dashboardResourceClient.createPartialDashboard().build();
            var id1 = dashboardResourceClient.create(dashboard1, API_KEY, TEST_WORKSPACE_NAME);

            var dashboard2 = dashboardResourceClient.createPartialDashboard().build();
            var id2 = dashboardResourceClient.create(dashboard2, API_KEY, TEST_WORKSPACE_NAME);

            var dashboard3 = dashboardResourceClient.createPartialDashboard().build();
            var id3 = dashboardResourceClient.create(dashboard3, API_KEY, TEST_WORKSPACE_NAME);

            // Verify dashboards exist
            dashboardResourceClient.get(id1, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            dashboardResourceClient.get(id2, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            dashboardResourceClient.get(id3, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);

            // Batch delete dashboards
            dashboardResourceClient.batchDelete(Set.of(id1, id2, id3), API_KEY, TEST_WORKSPACE_NAME);

            // Verify dashboards are deleted
            dashboardResourceClient.get(id1, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
            dashboardResourceClient.get(id2, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
            dashboardResourceClient.get(id3, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Batch delete single dashboard")
        void batchDeleteSingleDashboard() {
            var dashboard = dashboardResourceClient.createPartialDashboard().build();
            var id = dashboardResourceClient.create(dashboard, API_KEY, TEST_WORKSPACE_NAME);

            // Verify dashboard exists
            dashboardResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);

            // Batch delete single dashboard
            dashboardResourceClient.batchDelete(Set.of(id), API_KEY, TEST_WORKSPACE_NAME);

            // Verify dashboard is deleted
            dashboardResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Batch delete with non-existent IDs returns 204")
        void batchDeleteWithNonExistentIdsReturns204() {
            var nonExistentId1 = UUID.randomUUID();
            var nonExistentId2 = UUID.randomUUID();

            dashboardResourceClient.batchDelete(Set.of(nonExistentId1, nonExistentId2), API_KEY, TEST_WORKSPACE_NAME,
                    HttpStatus.SC_NO_CONTENT);
        }

        @Test
        @DisplayName("Batch delete with mixed existing and non-existent IDs")
        void batchDeleteWithMixedIds() {
            var dashboard = dashboardResourceClient.createPartialDashboard().build();
            var existingId = dashboardResourceClient.create(dashboard, API_KEY, TEST_WORKSPACE_NAME);
            var nonExistentId = UUID.randomUUID();

            // Verify dashboard exists
            dashboardResourceClient.get(existingId, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);

            // Batch delete with mixed IDs
            dashboardResourceClient.batchDelete(Set.of(existingId, nonExistentId), API_KEY, TEST_WORKSPACE_NAME);

            // Verify existing dashboard is deleted
            dashboardResourceClient.get(existingId, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Batch delete from different workspace returns 204 but doesn't delete")
        void batchDeleteFromDifferentWorkspaceReturns204() {
            // Create dashboards in one workspace
            var dashboard1 = dashboardResourceClient.createPartialDashboard().build();
            var id1 = dashboardResourceClient.create(dashboard1, API_KEY, TEST_WORKSPACE_NAME);

            var dashboard2 = dashboardResourceClient.createPartialDashboard().build();
            var id2 = dashboardResourceClient.create(dashboard2, API_KEY, TEST_WORKSPACE_NAME);

            // Try to delete from a different workspace
            String differentApiKey = UUID.randomUUID().toString();
            String differentWorkspaceName = "different-workspace-" + UUID.randomUUID();
            String differentWorkspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(differentApiKey, differentWorkspaceName, differentWorkspaceId);

            dashboardResourceClient.batchDelete(Set.of(id1, id2), differentApiKey, differentWorkspaceName,
                    HttpStatus.SC_NO_CONTENT);

            // Verify original dashboards still exist
            dashboardResourceClient.get(id1, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            dashboardResourceClient.get(id2, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
        }
    }

    private void assertDashboard(Dashboard expected, Dashboard actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(DASHBOARD_IGNORED_FIELDS)
                .isEqualTo(expected);

        assertThat(actual.createdAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
        assertThat(actual.lastUpdatedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
    }
}
