package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AnalyticsQueryResponse;
import com.comet.opik.api.DashboardSavedQuery;
import com.comet.opik.api.Trace;
import com.comet.opik.api.error.ErrorMessage;
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
import com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Public Analytics Queries Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class AnalyticsQueriesPublicResourceTest {

    private static final String USER = UUID.randomUUID().toString();

    private static final String API_KEY_A = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_A = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_A = UUID.randomUUID().toString();

    private static final String API_KEY_B = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_B = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_B = UUID.randomUUID().toString();

    private static final String RESULT_QUERY = "SELECT toJSONString(map('id', toString(id), 'workspace_id', workspace_id, 'project_id', toString(project_id))) AS result FROM traces ORDER BY id";

    // Per workspace. Above what workspace A spends across this class, so only the rate-limit test reaches it.
    private static final int RATE_LIMIT = 20;
    private static final String RATE_LIMIT_BUCKET = "analyticsQueries";
    private static final String RATE_LIMIT_MESSAGE = "You have exceeded the rate limit for analytics queries in this workspace. Please try again later.";

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
                        .rateLimitEnabled(true)
                        .limit(10_000L)
                        .workspaceLimit(10_000L)
                        .limitDurationInSeconds(60L)
                        .customLimits(Map.of(RATE_LIMIT_BUCKET, new LimitConfig("Analytics-Queries",
                                "analytics_queries", RATE_LIMIT, 60, RATE_LIMIT_MESSAGE)))
                        .customConfigs(List.of(
                                new CustomConfig("serviceToggles.ollieEnabled", "true"),
                                new CustomConfig("customCharts.enabled", "true"),
                                new CustomConfig("customCharts.publicEndpointsEnabled", "true"),
                                // On so the redaction refusal can be reached; callers holding original_data_view
                                // are unaffected.
                                new CustomConfig("redaction.enabled", "true"),
                                new CustomConfig("redaction.rules", """
                                        [{"regex":"\\\\d{3}-\\\\d{3}-\\\\d{4}","replace":"[PHONE]"}]""")))
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

        mockOriginalDataViewer(API_KEY_A, WORKSPACE_NAME_A, WORKSPACE_ID_A);
        mockOriginalDataViewer(API_KEY_B, WORKSPACE_NAME_B, WORKSPACE_ID_B);

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

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
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
        assertScopedRows(analyticsQueriesClient.run(projectIdA, RESULT_QUERY, API_KEY_A, WORKSPACE_NAME_A),
                WORKSPACE_ID_A, projectIdA, traceIdA);
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
        // In a subquery: a top-level SETTINGS is applied to the EXPLAIN itself and refused by the readonly profile
        // before the AST check sees it.
        var query = "SELECT toJSONString(map('id', toString(id))) AS result FROM (SELECT id FROM traces SETTINGS max_threads = 1)";

        try (Response response = analyticsQueriesClient.callRun(null, query, API_KEY_A, WORKSPACE_NAME_A)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(response.readEntity(ErrorMessage.class).errors())
                    .containsExactly("Query rejected: SETTINGS/SET clauses are not allowed");
        }
    }

    @Test
    @DisplayName("a query at exactly the size cap is accepted")
    void runQuery__whenQueryAtSizeCap__thenAccepted() {
        var query = RESULT_QUERY + " ".repeat(DashboardSavedQuery.MAX_SQL_LENGTH - RESULT_QUERY.length());

        assertScopedRows(analyticsQueriesClient.run(projectIdA, query, API_KEY_A, WORKSPACE_NAME_A),
                WORKSPACE_ID_A, projectIdA, traceIdA);
    }

    @Test
    @DisplayName("a query over the size cap fails request validation with 422")
    void runQuery__whenQueryTooLong__thenUnprocessable() {
        var query = RESULT_QUERY + " ".repeat(DashboardSavedQuery.MAX_SQL_LENGTH);

        try (Response response = analyticsQueriesClient.callRun(null, query, API_KEY_A, WORKSPACE_NAME_A)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
            assertThat(response.readEntity(ErrorMessage.class).errors())
                    .containsExactly(
                            "query size must be between 0 and %d".formatted(DashboardSavedQuery.MAX_SQL_LENGTH));
        }
    }

    @Test
    @DisplayName("a caller without project data view is refused with 403")
    void runQuery__whenProjectDataViewDenied__thenForbidden() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), apiKey, workspaceName,
                WorkspaceUserPermission.PROJECT_DATA_VIEW.getValue());

        try (Response response = analyticsQueriesClient.callRun(null, RESULT_QUERY, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
        }
    }

    @Test
    @DisplayName("a caller whose responses are redacted is refused with 403, since free-form SQL cannot be masked")
    void runQuery__whenCallerRedacted__thenForbidden() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspaceWithPermissions(wireMock.server(), apiKey, workspaceName,
                UUID.randomUUID().toString(), USER, List.of());

        try (Response response = analyticsQueriesClient.callRun(null, RESULT_QUERY, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            assertThat(response.readEntity(String.class))
                    .contains(WorkspaceUserPermission.ORIGINAL_DATA_VIEW.getValue());
        }
    }

    @Test
    @DisplayName("429 once the workspace's own analytics-queries budget is spent")
    void runQuery__whenWorkspaceLimitReached__thenTooManyRequests() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockOriginalDataViewer(apiKey, workspaceName, UUID.randomUUID().toString());

        for (int i = 0; i < RATE_LIMIT; i++) {
            try (Response response = analyticsQueriesClient.callRun(null, RESULT_QUERY, apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            }
        }
        try (Response response = analyticsQueriesClient.callRun(null, RESULT_QUERY, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_TOO_MANY_REQUESTS);
            assertThat(response.readEntity(String.class)).contains(RATE_LIMIT_MESSAGE);
        }
    }

    private void mockOriginalDataViewer(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspaceWithPermissions(wireMock.server(), apiKey, workspaceName, workspaceId, USER,
                List.of(WorkspaceUserPermission.ORIGINAL_DATA_VIEW.getValue()));
    }

    private void assertScopedRows(AnalyticsQueryResponse response, String expectedWorkspaceId,
            UUID expectedProjectId, UUID... expectedTraceIds) {
        assertThat(response.results())
                .allSatisfy(node -> {
                    assertThat(node.get("workspace_id").asText()).isEqualTo(expectedWorkspaceId);
                    assertThat(node.get("project_id").asText()).isEqualTo(expectedProjectId.toString());
                })
                .extracting(node -> node.get("id").asText())
                .containsExactly(Arrays.stream(expectedTraceIds).map(UUID::toString).toArray(String[]::new));
    }

    private UUID createTrace(TraceResourceClient traceResourceClient, String projectName, String apiKey,
            String workspaceName) {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .build();
        return traceResourceClient.createTrace(trace, apiKey, workspaceName);
    }
}
