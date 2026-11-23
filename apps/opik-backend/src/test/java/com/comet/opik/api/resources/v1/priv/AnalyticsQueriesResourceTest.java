package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DashboardSavedQuery;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AnalyticsQueriesClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Public Analytics Queries Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class AnalyticsQueriesResourceTest {

    private static final String USER = UUID.randomUUID().toString();

    private static final String API_KEY_A = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_A = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_A = UUID.randomUUID().toString();

    private static final String API_KEY_B = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_B = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_B = UUID.randomUUID().toString();

    private static final String RESULT_QUERY = "SELECT toJSONString(map('id', toString(id), 'workspace_id', workspace_id, 'project_id', toString(project_id))) AS result FROM traces ORDER BY id";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("serviceToggles.ollieEnabled", "true"),
                                new CustomConfig("customCharts.enabled", "true"),
                                new CustomConfig("customCharts.publicEndpointsEnabled", "true")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private AnalyticsQueriesClient analyticsQueriesClient;

    private UUID projectIdA;
    private UUID projectIdB;
    private UUID traceIdA;
    private UUID traceIdA2;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY_A, WORKSPACE_NAME_A, WORKSPACE_ID_A, USER);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY_B, WORKSPACE_NAME_B, WORKSPACE_ID_B, USER);

        var projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        var traceResourceClient = new TraceResourceClient(client, baseURI);
        analyticsQueriesClient = new AnalyticsQueriesClient(client, baseURI);

        String projectNameA = UUID.randomUUID().toString();
        projectIdA = projectResourceClient.createProject(projectNameA, API_KEY_A, WORKSPACE_NAME_A);
        traceIdA = createTrace(traceResourceClient, projectNameA, API_KEY_A, WORKSPACE_NAME_A);

        String projectNameA2 = UUID.randomUUID().toString();
        projectResourceClient.createProject(projectNameA2, API_KEY_A, WORKSPACE_NAME_A);
        traceIdA2 = createTrace(traceResourceClient, projectNameA2, API_KEY_A, WORKSPACE_NAME_A);

        String projectNameB = UUID.randomUUID().toString();
        projectIdB = projectResourceClient.createProject(projectNameB, API_KEY_B, WORKSPACE_NAME_B);
        createTrace(traceResourceClient, projectNameB, API_KEY_B, WORKSPACE_NAME_B);
    }

    @Test
    @DisplayName("without a project, returns every project of the caller's workspace and nothing else")
    void runQuery__whenNoProject__thenReturnsWholeWorkspace() {
        var response = analyticsQueriesClient.run(null, RESULT_QUERY, API_KEY_A, WORKSPACE_NAME_A);

        assertThat(response.results())
                .allSatisfy(node -> assertThat(node.get("workspace_id").asText()).isEqualTo(WORKSPACE_ID_A))
                .extracting(node -> node.get("id").asText())
                .containsExactlyInAnyOrder(traceIdA.toString(), traceIdA2.toString());
    }

    @Test
    @DisplayName("a supplied project narrows the result to it")
    void runQuery__whenProject__thenReturnsOnlyThatProject() {
        var response = analyticsQueriesClient.run(projectIdA, RESULT_QUERY, API_KEY_A, WORKSPACE_NAME_A);

        assertThat(response.results())
                .extracting(node -> node.get("id").asText())
                .containsExactly(traceIdA.toString());
    }

    @Test
    @DisplayName("a project from another workspace returns no rows")
    void runQuery__whenProjectFromAnotherWorkspace__thenReturnsNoRows() {
        assertThat(analyticsQueriesClient.run(projectIdB, RESULT_QUERY, API_KEY_A, WORKSPACE_NAME_A).results())
                .isEmpty();
    }

    @Test
    @DisplayName("a query the AST check rejects returns 400")
    void runQuery__whenSettingsClause__thenBadRequest() {
        var query = "SELECT toJSONString(map('id', toString(id))) AS result FROM traces SETTINGS max_threads = 1";

        try (Response response = analyticsQueriesClient.callRun(null, query, API_KEY_A, WORKSPACE_NAME_A)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        }
    }

    @Test
    @DisplayName("a query over the size cap returns 422 before reaching ClickHouse")
    void runQuery__whenQueryTooLong__thenUnprocessable() {
        var query = RESULT_QUERY + " ".repeat(DashboardSavedQuery.MAX_SQL_LENGTH);

        try (Response response = analyticsQueriesClient.callRun(null, query, API_KEY_A, WORKSPACE_NAME_A)) {
            assertThat(response.getStatus()).isEqualTo(422);
        }
    }

    private UUID createTrace(TraceResourceClient traceResourceClient, String projectName, String apiKey,
            String workspaceName) {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .build();
        return traceResourceClient.createTrace(trace, apiKey, workspaceName);
    }
}
