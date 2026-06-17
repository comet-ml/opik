package com.comet.opik.api.resources.v1.internal;

import com.comet.opik.api.AnalyticsQueryResponse;
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
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Analytics Queries Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class AnalyticsQueriesResourceTest {

    private static final String RO_USER = "comet_readonly_freeform_sql_user";
    private static final String RO_PASS = "freeform_sql_test_pass";
    private static final String RO_PROFILE = "comet_llm_readonly_freeform_sql_profile";

    private static final String USER = UUID.randomUUID().toString();

    // Two tenants seeded with one trace each, used to assert cross-tenant isolation.
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
                                new CustomConfig("serviceToggles.agentInsightsEnabled", "true"),
                                new CustomConfig("databaseAnalyticsReadOnlyFreeFormSql.username", RO_USER),
                                new CustomConfig("databaseAnalyticsReadOnlyFreeFormSql.password", RO_PASS)))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private AnalyticsQueriesClient analyticsQueriesClient;

    private UUID projectIdA;
    private UUID projectIdB;
    private UUID traceIdA;
    private UUID traceIdB;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplateAsync clickHouseTemplate) {
        var baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);

        mockWorkspace(API_KEY_A, WORKSPACE_NAME_A, WORKSPACE_ID_A);
        mockWorkspace(API_KEY_B, WORKSPACE_NAME_B, WORKSPACE_ID_B);

        projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        traceResourceClient = new TraceResourceClient(client, baseURI);
        analyticsQueriesClient = new AnalyticsQueriesClient(client, baseURI);

        // Replicate the OPIK-6846 provisioning in the test container: a readonly user restricted to the 3 tables,
        // with row policies that bind every query to the SQL_workspace_id / SQL_project_id custom settings.
        provisionReadOnlyUser(clickHouseTemplate);

        String projectNameA = UUID.randomUUID().toString();
        projectIdA = projectResourceClient.createProject(projectNameA, API_KEY_A, WORKSPACE_NAME_A);
        traceIdA = createTrace(projectNameA, API_KEY_A, WORKSPACE_NAME_A);

        String projectNameB = UUID.randomUUID().toString();
        projectIdB = projectResourceClient.createProject(projectNameB, API_KEY_B, WORKSPACE_NAME_B);
        traceIdB = createTrace(projectNameB, API_KEY_B, WORKSPACE_NAME_B);
    }

    @Test
    @DisplayName("returns only the rows of the bound workspace/project")
    void executeQuery__whenBoundToWorkspaceProject__thenReturnsOnlyThatScope() {
        assertScopedRows(analyticsQueriesClient.execute(projectIdA, RESULT_QUERY, API_KEY_A, WORKSPACE_NAME_A),
                WORKSPACE_ID_A, projectIdA, traceIdA);
        assertScopedRows(analyticsQueriesClient.execute(projectIdB, RESULT_QUERY, API_KEY_B, WORKSPACE_NAME_B),
                WORKSPACE_ID_B, projectIdB, traceIdB);
    }

    @Test
    @DisplayName("a project from another workspace returns no rows")
    void executeQuery__whenProjectFromAnotherWorkspace__thenReturnsNoRows() {
        assertNoRows(analyticsQueriesClient.execute(projectIdB, RESULT_QUERY, API_KEY_A, WORKSPACE_NAME_A));
    }

    @Test
    @DisplayName("a workspace querying another workspace's project returns no rows")
    void executeQuery__whenWorkspaceMismatch__thenReturnsNoRows() {
        assertNoRows(analyticsQueriesClient.execute(projectIdA, RESULT_QUERY, API_KEY_B, WORKSPACE_NAME_B));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT toJSONString(map('id', toString(id))) AS result FROM traces SETTINGS max_threads = 1",
            "SELECT toJSONString(map('id', toString(id))) AS result FROM (SELECT id FROM traces SETTINGS max_threads = 1)",
            "WITH t AS (SELECT id FROM traces SETTINGS max_threads = 1) SELECT toJSONString(map('id', toString(id))) AS result FROM t"
    })
    @DisplayName("a query carrying SETTINGS is rejected by the AST guard")
    void executeQuery__whenQueryContainsSettings__thenRejected(String query) {
        assertBadRequest(query);
    }

    @Test
    @DisplayName("a Set-prefixed identifier is not mistaken for a SETTINGS node")
    void executeQuery__whenSetPrefixedIdentifier__thenAccepted() {
        // The AST guard matches the exact `Set` node token, so a `settings`-named CTE must not be falsely rejected.
        var query = "WITH settings AS (SELECT id, workspace_id, project_id FROM traces) "
                + "SELECT toJSONString(map('id', toString(id), 'workspace_id', workspace_id, 'project_id', toString(project_id))) AS result FROM settings";
        assertScopedRows(analyticsQueriesClient.execute(projectIdA, query, API_KEY_A, WORKSPACE_NAME_A),
                WORKSPACE_ID_A, projectIdA, traceIdA);
    }

    @Test
    @DisplayName("an unparseable query is rejected")
    void executeQuery__whenQueryUnparseable__thenRejected() {
        assertBadRequest("SELECT FROM WHERE");
    }

    @Test
    @DisplayName("statement stacking is rejected")
    void executeQuery__whenStatementStacking__thenRejected() {
        assertBadRequest("SELECT 1 AS result; SELECT 2 AS result");
    }

    @Test
    @DisplayName("a write statement is rejected by the read-only profile")
    void executeQuery__whenWriteStatement__thenRejected() {
        assertBadRequest("INSERT INTO traces (id, workspace_id, project_id, name) VALUES ('%s', '%s', '%s', 'x')"
                .formatted(UUID.randomUUID(), WORKSPACE_ID_A, projectIdA));
    }

    @Test
    @DisplayName("a query against a non-granted table fails on permissions")
    void executeQuery__whenNonGrantedTable__thenRejected() {
        assertBadRequest("SELECT toJSONString(map('id', toString(id))) AS result FROM experiments");
    }

    @Test
    @DisplayName("a query against a system table is rejected (row policies don't cover system.*)")
    void executeQuery__whenSystemTable__thenRejected() {
        // system.* is outside the row policies, so isolation relies on the read-only user not being granted access.
        assertBadRequest("SELECT toJSONString(map('q', query)) AS result FROM system.query_log");
    }

    private void assertBadRequest(String query) {
        try (Response response = analyticsQueriesClient.callExecute(projectIdA, query, API_KEY_A, WORKSPACE_NAME_A)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        }
    }

    private void assertScopedRows(AnalyticsQueryResponse response, String expectedWorkspaceId, UUID expectedProjectId,
            UUID... expectedTraceIds) {
        assertThat(response.results())
                .allSatisfy(node -> {
                    assertThat(node.get("workspace_id").asText()).isEqualTo(expectedWorkspaceId);
                    assertThat(node.get("project_id").asText()).isEqualTo(expectedProjectId.toString());
                })
                .extracting(node -> node.get("id").asText())
                .containsExactlyInAnyOrder(
                        Stream.of(expectedTraceIds).map(UUID::toString).toArray(String[]::new));
    }

    private void assertNoRows(AnalyticsQueryResponse response) {
        assertThat(response.results()).isEmpty();
    }

    private UUID createTrace(String projectName, String apiKey, String workspaceName) {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .build();
        return traceResourceClient.createTrace(trace, apiKey, workspaceName);
    }

    private void mockWorkspace(String apiKey, String workspaceName, String workspaceId) {
        // Standard stub for the /v1/private/* endpoints used to seed projects and traces.
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);

        // The default AuthTestUtils stub only matches /v1/private/*; this endpoint authenticates under /v1/internal/*.
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/auth"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(apiKey))
                .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)))
                .withRequestBody(matchingJsonPath("$.path", matching("/v1/internal/analytics-queries(/.*)?")))
                .willReturn(okJson(AuthTestUtils.newWorkspaceAuthResponse(USER, workspaceId, workspaceName, null))));
    }

    private void provisionReadOnlyUser(TransactionTemplateAsync clickHouseTemplate) {
        // The read-only sandbox profile (limits + CHANGEABLE_IN_READONLY custom settings) is defined in clickhouse.xml;
        // here we only create the user and bind it to that profile, then grant access via SQL-managed RBAC below.
        executeStatement(clickHouseTemplate,
                "CREATE USER IF NOT EXISTS %s IDENTIFIED BY '%s' SETTINGS PROFILE '%s'".formatted(RO_USER, RO_PASS,
                        RO_PROFILE));

        for (String table : List.of("spans", "traces", "authored_feedback_scores")) {
            executeStatement(clickHouseTemplate,
                    "GRANT SELECT ON %s.%s TO %s".formatted(DATABASE_NAME, table, RO_USER));
            executeStatement(clickHouseTemplate, """
                    CREATE ROW POLICY IF NOT EXISTS %s_workspace_project_isolation ON %s.%s
                    FOR SELECT USING
                        workspace_id = getSetting('SQL_workspace_id')
                        AND project_id = getSetting('SQL_project_id')
                    AS RESTRICTIVE TO %s
                    """.formatted(table, DATABASE_NAME, table, RO_USER));
        }
    }

    private void executeStatement(TransactionTemplateAsync clickHouseTemplate, String sql) {
        clickHouseTemplate.nonTransaction(connection -> Mono.from(connection.createStatement(sql).execute())).block();
    }
}
