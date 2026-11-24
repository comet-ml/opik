package com.comet.opik.api.resources.v1.internal;

import com.comet.opik.api.AnalyticsQueryResponse;
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
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static com.comet.opik.domain.FreeFormSqlQueryService.ACCESS_DENIED_MESSAGE;
import static com.comet.opik.domain.FreeFormSqlQueryService.EXECUTION_FAILED_MESSAGE;
import static com.comet.opik.domain.FreeFormSqlQueryService.INVALID_JSON_RESULT_MESSAGE;
import static com.comet.opik.domain.FreeFormSqlQueryService.PARSE_ERROR_MESSAGE;
import static com.comet.opik.domain.FreeFormSqlQueryService.RESULT_SHAPE_MESSAGE;
import static com.comet.opik.domain.FreeFormSqlQueryService.SETTINGS_CLAUSE_MESSAGE;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Analytics Queries Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class AnalyticsQueriesResourceTest {

    private enum Endpoint {
        EXECUTE,
        EXECUTE_SCOPED
    }

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
                        // The read-only free-form SQL user is provisioned globally on the ClickHouse container
                        // (users.xml) and wired by config-test.yml; the test only needs to flip the toggle on.
                        .customConfigs(List.of(
                                new CustomConfig("serviceToggles.ollieEnabled", "true"),
                                new CustomConfig("customCharts.enabled", "true")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private AnalyticsQueriesClient analyticsQueriesClient;
    private ExperimentResourceClient experimentResourceClient;

    private UUID projectIdA;
    private UUID projectIdA2;
    private UUID projectIdB;
    private UUID traceIdA;
    private UUID traceIdA2;
    private UUID traceIdB;
    private UUID experimentIdA;
    private UUID experimentIdB;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);

        mockWorkspace(API_KEY_A, WORKSPACE_NAME_A, WORKSPACE_ID_A);
        mockWorkspace(API_KEY_B, WORKSPACE_NAME_B, WORKSPACE_ID_B);

        projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        traceResourceClient = new TraceResourceClient(client, baseURI);
        analyticsQueriesClient = new AnalyticsQueriesClient(client, baseURI);
        experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);

        String projectNameA = UUID.randomUUID().toString();
        projectIdA = projectResourceClient.createProject(projectNameA, API_KEY_A, WORKSPACE_NAME_A);
        traceIdA = createTrace(projectNameA, API_KEY_A, WORKSPACE_NAME_A);

        String projectNameB = UUID.randomUUID().toString();
        projectIdB = projectResourceClient.createProject(projectNameB, API_KEY_B, WORKSPACE_NAME_B);
        traceIdB = createTrace(projectNameB, API_KEY_B, WORKSPACE_NAME_B);

        // A second project in A, so the scoped endpoint has something to narrow away.
        String projectNameA2 = UUID.randomUUID().toString();
        projectIdA2 = projectResourceClient.createProject(projectNameA2, API_KEY_A, WORKSPACE_NAME_A);
        traceIdA2 = createTrace(projectNameA2, API_KEY_A, WORKSPACE_NAME_A);

        // A's experiment sits in A2, so a query scoped to project A returning it shows experiments ignore the project.
        experimentIdA = experimentResourceClient.create(
                experimentResourceClient.createPartialExperiment().projectId(projectIdA2).build(),
                API_KEY_A, WORKSPACE_NAME_A);
        experimentIdB = experimentResourceClient.create(API_KEY_B, WORKSPACE_NAME_B);
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

    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource
    @DisplayName("invalid or disallowed queries return the exact error through both HTTP endpoints")
    void executeQuery__whenInvalid__thenBadRequest(String label, Endpoint endpoint, String query,
            String expectedError) {
        assertRejected(endpoint, query, expectedError);
    }

    private Stream<Arguments> executeQuery__whenInvalid__thenBadRequest() {
        // The top-level SETTINGS case is rejected by ClickHouse during EXPLAIN AST, before the AST guard can inspect it.
        // Nested SETTINGS clauses are rejected by the AST guard. The other cases exercise the parser, grants, and
        // single-JSON-column result contract.
        return Stream.of(
                arguments("SETTINGS clause at top level",
                        "SELECT toJSONString(map('id', toString(id))) AS result FROM traces SETTINGS max_threads = 1",
                        EXECUTION_FAILED_MESSAGE),
                arguments("SETTINGS clause in a subquery",
                        "SELECT toJSONString(map('id', toString(id))) AS result FROM (SELECT id FROM traces SETTINGS max_threads = 1)",
                        SETTINGS_CLAUSE_MESSAGE),
                arguments("SETTINGS clause in a CTE",
                        "WITH t AS (SELECT id FROM traces SETTINGS max_threads = 1) SELECT toJSONString(map('id', toString(id))) AS result FROM t",
                        SETTINGS_CLAUSE_MESSAGE),
                arguments("unparseable query", "SELECT FROM WHERE", PARSE_ERROR_MESSAGE),
                arguments("statement stacking", "SELECT 1 AS result; SELECT 2 AS result",
                        PARSE_ERROR_MESSAGE),
                arguments("write statement (read-only profile)",
                        "INSERT INTO traces (id, workspace_id, project_id, name) VALUES ('%s', '%s', '%s', 'x')"
                                .formatted(UUID.randomUUID(), WORKSPACE_ID_A, projectIdA),
                        EXECUTION_FAILED_MESSAGE),
                arguments("non-granted table",
                        "SELECT toJSONString(map('n', toString(count()))) AS result FROM span_weeks",
                        ACCESS_DENIED_MESSAGE),
                arguments("extra column beside result",
                        "SELECT toJSONString(map('n', '1')) AS result, 1 AS extra", RESULT_SHAPE_MESSAGE),
                arguments("single column not named result",
                        "SELECT toJSONString(map('n', '1')) AS wrong", RESULT_SHAPE_MESSAGE),
                arguments("non-JSON result value", "SELECT 'not json{' AS result FROM traces",
                        INVALID_JSON_RESULT_MESSAGE),
                arguments("system table (outside row policies)",
                        "SELECT toJSONString(map('q', query)) AS result FROM system.query_log", ACCESS_DENIED_MESSAGE))
                .flatMap(testCase -> Stream.of(Endpoint.values())
                        .map(endpoint -> arguments(testCase.get()[0], endpoint, testCase.get()[1], testCase.get()[2])));
    }

    private Stream<Arguments> forbiddenQueries() {
        var catalogTables = Stream.of("system.tables", "system.columns", "system.databases", "system.settings",
                "system.one", "information_schema.tables")
                .map(table -> arguments("catalog table " + table,
                        "SELECT toJSONString(map('n', toString(count()))) AS result FROM " + table));
        var metadataExpressions = Stream.of("currentUser()", "database()", "schema()", "current_database()",
                "current_user()", "current_user", "getMacro('replica')", "getServerPort('tcp_port')",
                "displayName()", "DISPLAYNAME()", "enabledRoles()", "defaultRoles()", "getOSKernelVersion()",
                "tcpPort()", "TCPPORT()", "authenticatedUser()", "enabledProfiles()", "defaultProfiles()",
                "current_database", "database", "schema", "user", "currentDatabase", "currentUser")
                .map(expression -> arguments("metadata expression " + expression,
                        "SELECT toJSONString(map('n', toString(%s))) AS result".formatted(expression)));
        var nested = Stream.of(
                arguments("catalog table in CTE", """
                        WITH hidden AS (SELECT count() AS n FROM system.tables)
                        SELECT toJSONString(map('n', toString(n))) AS result FROM hidden
                        """),
                arguments("catalog table in FROM subquery", """
                        SELECT toJSONString(map('n', toString(n))) AS result
                        FROM (SELECT count() AS n FROM system.tables)
                        """),
                arguments("catalog table in UNION arm", """
                        SELECT toJSONString(map('n', '1')) AS result
                        UNION ALL SELECT toJSONString(map('n', toString(count()))) AS result FROM system.tables
                        """),
                arguments("catalog table in WHERE subquery", """
                        SELECT toJSONString(map('n', '1')) AS result FROM traces
                        WHERE EXISTS (SELECT 1 FROM system.tables)
                        """),
                arguments("metadata function in CTE", """
                        WITH hidden AS (SELECT currentUser() AS n)
                        SELECT toJSONString(map('n', toString(n))) AS result FROM hidden
                        """),
                arguments("metadata function in FROM subquery", """
                        SELECT toJSONString(map('n', toString(n))) AS result
                        FROM (SELECT currentUser() AS n)
                        """),
                arguments("metadata function in UNION arm", """
                        SELECT toJSONString(map('n', '1')) AS result
                        UNION ALL SELECT toJSONString(map('n', currentUser())) AS result
                        """),
                arguments("metadata function in WHERE", """
                        SELECT toJSONString(map('n', '1')) AS result FROM traces
                        WHERE currentUser() != ''
                        """));
        return Stream.concat(Stream.concat(catalogTables, metadataExpressions), nested);
    }

    private Stream<Arguments> forbiddenQueriesByEndpoint() {
        return forbiddenQueries().flatMap(testCase -> Stream.of(Endpoint.values())
                .map(endpoint -> arguments(testCase.get()[0], endpoint, testCase.get()[1])));
    }

    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource("forbiddenQueriesByEndpoint")
    @DisplayName("catalog and metadata reads are denied through both HTTP endpoints")
    void forbiddenQuery__thenAccessDenied(String label, Endpoint endpoint, String query) {
        assertRejected(endpoint, query, ACCESS_DENIED_MESSAGE);
    }

    private Stream<Arguments> catalogTableFunctionsByEndpoint() {
        return Stream.of(
                arguments("merge with string catalog", "merge('system', '^tables$')"),
                arguments("merge with identifier catalog", "merge(system, '^tables$')"),
                arguments("remote catalog", "remote('localhost', 'system', 'tables')"),
                arguments("cluster catalog", "cluster('default', 'system', 'tables')"))
                .flatMap(testCase -> Stream.of(Endpoint.values()).map(endpoint -> arguments(testCase.get()[0], endpoint,
                        "SELECT toJSONString(map('n', toString(count()))) AS result FROM " + testCase.get()[1])));
    }

    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource("catalogTableFunctionsByEndpoint")
    @DisplayName("table functions are rejected before ClickHouse resolves their arguments")
    void catalogTableFunction__thenAccessDenied(String label, Endpoint endpoint, String query) {
        assertRejected(endpoint, query, ACCESS_DENIED_MESSAGE);
    }

    private Stream<Arguments> allowedQueries() {
        return Stream.of(
                arguments("plain FROM-less query", "SELECT toJSONString(map('n', '1')) AS result"),
                arguments("ARRAY JOIN over a literal array", """
                        SELECT toJSONString(map('n', toString(x))) AS result
                        FROM (SELECT [1] AS a) ARRAY JOIN a AS x
                        """),
                arguments("ARRAY JOIN over a table column", """
                        SELECT toJSONString(map('n', '1')) AS result
                        FROM (SELECT count() AS c FROM traces ARRAY JOIN tags AS tag)
                        """),
                arguments("metadata-named column currentUser", """
                        SELECT toJSONString(map('n', toString(currentUser))) AS result
                        FROM (SELECT 1 AS currentUser)
                        """),
                arguments("metadata-named column currentDatabase", """
                        SELECT toJSONString(map('n', toString(currentDatabase))) AS result
                        FROM (SELECT 1 AS currentDatabase)
                        """),
                arguments("metadata-named alias currentUser", """
                        WITH 1 AS currentUser
                        SELECT toJSONString(map('n', toString(currentUser))) AS result
                        """),
                arguments("metadata-named alias currentDatabase", """
                        WITH 1 AS currentDatabase
                        SELECT toJSONString(map('n', toString(currentDatabase))) AS result
                        """),
                arguments("metadata-named CTE currentUser", """
                        WITH currentUser AS (SELECT 1 AS n)
                        SELECT toJSONString(map('n', toString(n))) AS result FROM currentUser
                        """),
                arguments("metadata-named CTE currentDatabase", """
                        WITH currentDatabase AS (SELECT 1 AS n)
                        SELECT toJSONString(map('n', toString(n))) AS result FROM currentDatabase
                        """));
    }

    private Stream<Arguments> allowedQueriesByEndpoint() {
        return allowedQueries().flatMap(testCase -> Stream.of(Endpoint.values())
                .map(endpoint -> arguments(testCase.get()[0], endpoint, testCase.get()[1])));
    }

    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource("allowedQueriesByEndpoint")
    @DisplayName("metadata-like identifiers and FROM-less queries remain valid through both HTTP endpoints")
    void allowedQuery__thenReturnsResult(String label, Endpoint endpoint, String query) {
        try (Response response = call(endpoint, query)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            assertThat(response.readEntity(AnalyticsQueryResponse.class).results())
                    .extracting(node -> node.get("n").asText())
                    .containsExactly("1");
        }
    }

    private Stream<Arguments> allowedTablesByEndpoint() {
        var standard = Stream.of("spans", "traces", "authored_feedback_scores")
                .map(table -> arguments(Endpoint.EXECUTE, table));
        var extended = Stream.of("spans", "traces", "authored_feedback_scores", "feedback_scores", "trace_threads",
                "experiments", "experiment_items", "dataset_items", "dataset_item_versions")
                .map(table -> arguments(Endpoint.EXECUTE_SCOPED, table));
        return Stream.concat(standard, extended);
    }

    @ParameterizedTest(name = "{0} reads {1}")
    @MethodSource("allowedTablesByEndpoint")
    @DisplayName("each account can query only its listed logical tables")
    void allowedTable__thenReturnsResult(Endpoint endpoint, String table) {
        String query = "SELECT toJSONString(map('n', toString(count()))) AS result FROM " + table;
        try (Response response = call(endpoint, query)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            assertThat(response.readEntity(AnalyticsQueryResponse.class).results())
                    .singleElement().satisfies(node -> assertThat(node.get("n").asText()).isNotBlank());
        }
    }

    @Test
    @DisplayName("a Set-prefixed identifier is not mistaken for a SETTINGS node")
    void executeQuery__whenSetPrefixedIdentifier__thenAccepted() {
        // The AST guard matches the exact `Set` node token, so a `settings`-named CTE must not be falsely rejected.
        var query = """
                WITH settings AS (SELECT id, workspace_id, project_id FROM traces)
                SELECT toJSONString(map('id', toString(id), 'workspace_id', workspace_id, 'project_id', toString(project_id))) AS result FROM settings
                """;
        assertScopedRows(analyticsQueriesClient.execute(projectIdA, query, API_KEY_A, WORKSPACE_NAME_A),
                WORKSPACE_ID_A, projectIdA, traceIdA);
    }

    @Test
    @DisplayName("scoped: without a project, returns every project of the bound workspace and nothing else")
    void executeScopedQuery__whenNoProject__thenReturnsWholeWorkspace() {
        var response = analyticsQueriesClient.executeScoped(null, RESULT_QUERY, API_KEY_A, WORKSPACE_NAME_A);

        assertThat(response.results())
                .allSatisfy(node -> assertThat(node.get("workspace_id").asText()).isEqualTo(WORKSPACE_ID_A))
                .extracting(node -> node.get("id").asText())
                .containsExactlyInAnyOrder(traceIdA.toString(), traceIdA2.toString());
    }

    @Test
    @DisplayName("scoped: a supplied project narrows the result to it")
    void executeScopedQuery__whenProject__thenReturnsOnlyThatProject() {
        assertScopedRows(analyticsQueriesClient.executeScoped(projectIdA, RESULT_QUERY, API_KEY_A, WORKSPACE_NAME_A),
                WORKSPACE_ID_A, projectIdA, traceIdA);
    }

    @Test
    @DisplayName("scoped: a project from another workspace returns no rows")
    void executeScopedQuery__whenProjectFromAnotherWorkspace__thenReturnsNoRows() {
        assertNoRows(analyticsQueriesClient.executeScoped(projectIdB, RESULT_QUERY, API_KEY_A, WORKSPACE_NAME_A));
    }

    @Test
    @DisplayName("scoped: experiments cover the whole workspace even when a project is supplied")
    void executeScopedQuery__whenExperimentsWithProject__thenNotNarrowedByProject() {
        var query = "SELECT toJSONString(map('id', toString(id), 'workspace_id', workspace_id)) AS result FROM experiments";

        var response = analyticsQueriesClient.executeScoped(projectIdA, query, API_KEY_A, WORKSPACE_NAME_A);

        assertThat(response.results())
                .extracting(node -> node.get("id").asText())
                .containsExactly(experimentIdA.toString())
                .doesNotContain(experimentIdB.toString());
    }

    private void assertRejected(Endpoint endpoint, String query, String expectedError) {
        try (Response response = call(endpoint, query)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
            assertThat(response.readEntity(ErrorMessage.class))
                    .isEqualTo(new ErrorMessage(List.of(expectedError)));
        }
    }

    private Response call(Endpoint endpoint, String query) {
        return switch (endpoint) {
            case EXECUTE -> analyticsQueriesClient.callExecute(projectIdA, query, API_KEY_A, WORKSPACE_NAME_A);
            case EXECUTE_SCOPED -> analyticsQueriesClient.callExecuteScoped(projectIdA, query, API_KEY_A,
                    WORKSPACE_NAME_A);
        };
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
}
