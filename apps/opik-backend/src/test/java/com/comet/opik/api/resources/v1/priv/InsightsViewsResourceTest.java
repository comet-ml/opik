package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DashboardScope;
import com.comet.opik.api.DashboardType;
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
import com.comet.opik.api.resources.utils.resources.InsightsViewResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
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
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class InsightsViewsResourceTest {

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

    private String baseURI;
    private InsightsViewResourceClient insightsViewClient;
    private DashboardResourceClient dashboardResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);

        this.insightsViewClient = new InsightsViewResourceClient(client, baseURI);
        this.dashboardResourceClient = new DashboardResourceClient(client, baseURI);

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
    @DisplayName("Create insights view")
    class CreateInsightsView {

        @Test
        @DisplayName("Create insights view forces INSIGHTS scope")
        void createInsightsViewForcesScope() {
            var dashboard = insightsViewClient.createPartialInsightsView()
                    .name("Insights View " + UUID.randomUUID())
                    .scope(DashboardScope.WORKSPACE) // Client sends WORKSPACE, but server should force INSIGHTS
                    .build();

            var created = insightsViewClient.createAndGet(dashboard, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(created.scope()).isEqualTo(DashboardScope.INSIGHTS);
            assertThat(created.name()).isEqualTo(dashboard.name());
            assertThat(created.id()).isNotNull();
            assertThat(created.slug()).isNotNull();
            assertThat(created.createdBy()).isEqualTo(USER);
        }

        @Test
        @DisplayName("Create insights view with default type")
        void createInsightsViewWithDefaultType() {
            var created = insightsViewClient.createAndGet(API_KEY, TEST_WORKSPACE_NAME);

            assertThat(created.scope()).isEqualTo(DashboardScope.INSIGHTS);
            assertThat(created.type()).isEqualTo(DashboardType.MULTI_PROJECT);
        }
    }

    @Nested
    @DisplayName("Get insights view")
    class GetInsightsView {

        @Test
        @DisplayName("Get insights view by id")
        void getInsightsViewById() {
            var id = insightsViewClient.create(API_KEY, TEST_WORKSPACE_NAME);

            var view = insightsViewClient.get(id, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);

            assertThat(view).isNotNull();
            assertThat(view.id()).isEqualTo(id);
            assertThat(view.scope()).isEqualTo(DashboardScope.INSIGHTS);
        }

        @Test
        @DisplayName("Get insights view returns 404 for dashboard-scoped entity")
        void getInsightsViewReturns404ForDashboard() {
            // Create a WORKSPACE-scoped dashboard via the dashboard endpoint
            var dashboardId = dashboardResourceClient.create(API_KEY, TEST_WORKSPACE_NAME);

            // Try to get it via insights endpoint - should return 404
            insightsViewClient.get(dashboardId, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Find insights views")
    class FindInsightsViews {

        @Test
        @DisplayName("Find insights views only returns INSIGHTS scope")
        void findInsightsViewsOnlyReturnsInsightsScope() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create via insights endpoint
            insightsViewClient.create(apiKey, workspaceName);
            insightsViewClient.create(apiKey, workspaceName);

            // Create via dashboard endpoint (WORKSPACE scope)
            dashboardResourceClient.create(apiKey, workspaceName);

            var page = insightsViewClient.find(apiKey, workspaceName, 1, 10, null, HttpStatus.SC_OK);

            // Should only see the 2 insights views, not the dashboard
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.content()).hasSize(2);
            page.content().forEach(d -> assertThat(d.scope()).isEqualTo(DashboardScope.INSIGHTS));
        }
    }

    @Nested
    @DisplayName("Update insights view")
    class UpdateInsightsView {

        @Test
        @DisplayName("Update insights view name")
        void updateInsightsViewName() {
            var id = insightsViewClient.create(API_KEY, TEST_WORKSPACE_NAME);

            var update = DashboardUpdate.builder()
                    .name("Updated Name " + UUID.randomUUID())
                    .build();

            var updated = insightsViewClient.updateAndGet(id, update, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(updated.name()).isEqualTo(update.name());
            assertThat(updated.scope()).isEqualTo(DashboardScope.INSIGHTS);
        }

        @Test
        @DisplayName("Update insights view returns 404 for dashboard-scoped entity")
        void updateInsightsViewReturns404ForDashboard() {
            var dashboardId = dashboardResourceClient.create(API_KEY, TEST_WORKSPACE_NAME);

            var update = DashboardUpdate.builder()
                    .name("Should Fail " + UUID.randomUUID())
                    .build();

            try (var response = insightsViewClient.callUpdate(dashboardId, update, API_KEY, TEST_WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }
    }

    @Nested
    @DisplayName("Delete insights view")
    class DeleteInsightsView {

        @Test
        @DisplayName("Delete insights view")
        void deleteInsightsView() {
            var id = insightsViewClient.create(API_KEY, TEST_WORKSPACE_NAME);

            insightsViewClient.delete(id, API_KEY, TEST_WORKSPACE_NAME);

            // Verify it's gone
            insightsViewClient.get(id, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Batch delete insights views")
        void batchDeleteInsightsViews() {
            var id1 = insightsViewClient.create(API_KEY, TEST_WORKSPACE_NAME);
            var id2 = insightsViewClient.create(API_KEY, TEST_WORKSPACE_NAME);

            insightsViewClient.batchDelete(Set.of(id1, id2), API_KEY, TEST_WORKSPACE_NAME);

            insightsViewClient.get(id1, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
            insightsViewClient.get(id2, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Delete insights view does not affect dashboard-scoped entity")
        void deleteInsightsViewDoesNotAffectDashboard() {
            var dashboardId = dashboardResourceClient.create(API_KEY, TEST_WORKSPACE_NAME);

            // Try to delete it via insights endpoint - should silently do nothing (delete returns 204 even if 0 rows)
            insightsViewClient.delete(dashboardId, API_KEY, TEST_WORKSPACE_NAME);

            // Dashboard should still exist
            dashboardResourceClient.get(dashboardId, API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
        }
    }
}
