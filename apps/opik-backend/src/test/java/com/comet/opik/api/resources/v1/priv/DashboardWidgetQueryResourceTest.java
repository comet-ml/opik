package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AnalyticsQueryResponse;
import com.comet.opik.api.Dashboard;
import com.comet.opik.api.DashboardSavedQuery;
import com.comet.opik.api.DashboardScope;
import com.comet.opik.api.DashboardWidgetQueryRequest;
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
import com.comet.opik.api.resources.utils.resources.DashboardResourceClient;
import com.comet.opik.api.resources.utils.resources.InsightsViewResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.validation.InRangeValidator;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static com.comet.opik.api.resources.utils.resources.DashboardTestDataFactory.createPartialDashboard;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dashboard Widget Query Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DashboardWidgetQueryResourceTest {

    private static final String USER = UUID.randomUUID().toString();

    // A is on the Custom Charts allowlist, B is not.
    private static final String API_KEY_A = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_A = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_A = UUID.randomUUID().toString();

    private static final String API_KEY_B = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_B = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_B = UUID.randomUUID().toString();

    private static final String WINDOWED_QUERY = "SELECT toJSONString(map('id', toString(id))) AS result FROM traces"
            + " WHERE start_time >= {{window_start}} AND start_time <= {{window_end}}";

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
                        // The read-only ClickHouse users come from users.xml on the container and config-test.yml.
                        .customConfigs(List.of(
                                new CustomConfig("serviceToggles.ollieEnabled", "true"),
                                new CustomConfig("customCharts.enabledWorkspaces", WORKSPACE_ID_A)))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ClientSupport client;
    private String baseURI;
    private DashboardResourceClient dashboardClient;
    private InsightsViewResourceClient insightsViewClient;
    private TraceResourceClient traceResourceClient;

    private UUID projectIdA;
    private UUID projectIdB;
    private UUID traceIdA;
    private Instant traceStartA;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.client = client;
        baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY_A, WORKSPACE_NAME_A, WORKSPACE_ID_A, USER);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY_B, WORKSPACE_NAME_B, WORKSPACE_ID_B, USER);
        mockDashboardSavedQueriesAuth(API_KEY_A, WORKSPACE_NAME_A, WORKSPACE_ID_A);
        mockDashboardSavedQueriesAuth(API_KEY_B, WORKSPACE_NAME_B, WORKSPACE_ID_B);

        var projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        dashboardClient = new DashboardResourceClient(client, baseURI);
        insightsViewClient = new InsightsViewResourceClient(client, baseURI);
        traceResourceClient = new TraceResourceClient(client, baseURI);

        String projectNameA = UUID.randomUUID().toString();
        projectIdA = projectResourceClient.createProject(projectNameA, API_KEY_A, WORKSPACE_NAME_A);
        traceStartA = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        traceIdA = createTrace(projectNameA, traceStartA, API_KEY_A, WORKSPACE_NAME_A);

        String projectNameB = UUID.randomUUID().toString();
        projectIdB = projectResourceClient.createProject(projectNameB, API_KEY_B, WORKSPACE_NAME_B);
        createTrace(projectNameB, traceStartA, API_KEY_B, WORKSPACE_NAME_B);
    }

    @Test
    @DisplayName("runs the dashboard saved query over the requested date range")
    void runWidgetQuery__whenRangeCoversTrace__thenReturnsIt() {
        String widgetId = UUID.randomUUID().toString();
        UUID dashboardId = dashboardClient.create(
                dashboard(widgetId, "ollie_chart", saveDashboardQuery(WINDOWED_QUERY, projectIdA)),
                API_KEY_A, WORKSPACE_NAME_A);

        var response = run(dashboardClient.callRunWidgetQuery(dashboardId, widgetId,
                window(traceStartA.minus(Duration.ofHours(1)), traceStartA.plus(Duration.ofHours(1))),
                API_KEY_A, WORKSPACE_NAME_A));

        assertThat(response.results()).extracting(node -> node.get("id").asText())
                .containsExactly(traceIdA.toString());
    }

    @Test
    @DisplayName("a date range that excludes the trace returns no rows")
    void runWidgetQuery__whenRangeExcludesTrace__thenNoRows() {
        String widgetId = UUID.randomUUID().toString();
        UUID dashboardId = dashboardClient.create(
                dashboard(widgetId, "ollie_chart", saveDashboardQuery(WINDOWED_QUERY, projectIdA)),
                API_KEY_A, WORKSPACE_NAME_A);

        var response = run(dashboardClient.callRunWidgetQuery(dashboardId, widgetId,
                window(traceStartA.plus(Duration.ofDays(1)), traceStartA.plus(Duration.ofDays(2))),
                API_KEY_A, WORKSPACE_NAME_A));

        assertThat(response.results()).isEmpty();
    }

    @Test
    @DisplayName("a saved project from another workspace returns no rows")
    void runWidgetQuery__whenProjectFromAnotherWorkspace__thenNoRows() {
        String widgetId = UUID.randomUUID().toString();
        UUID dashboardId = dashboardClient.create(
                dashboard(widgetId, "ollie_chart", saveDashboardQuery(WINDOWED_QUERY, projectIdB)),
                API_KEY_A, WORKSPACE_NAME_A);

        var response = run(dashboardClient.callRunWidgetQuery(dashboardId, widgetId, window(null, null),
                API_KEY_A, WORKSPACE_NAME_A));

        assertThat(response.results()).isEmpty();
    }

    @Test
    @DisplayName("runs a dashboard saved query on an insights view")
    void runWidgetQuery__whenInsightsView__thenReturnsRows() {
        String widgetId = UUID.randomUUID().toString();
        UUID viewId = insightsViewClient.create(
                dashboard(widgetId, "ollie_chart", saveDashboardQuery(WINDOWED_QUERY, projectIdA)).toBuilder()
                        .scope(DashboardScope.INSIGHTS).build(),
                API_KEY_A, WORKSPACE_NAME_A);

        var response = run(insightsViewClient.callRunWidgetQuery(viewId, widgetId, window(null, null),
                API_KEY_A, WORKSPACE_NAME_A));

        assertThat(response.results()).extracting(node -> node.get("id").asText())
                .containsExactly(traceIdA.toString());
    }

    @Test
    @DisplayName("an unknown widget, or one that is not an Ollie chart, is not found")
    void runWidgetQuery__whenNoSavedQuery__thenNotFound() {
        String widgetId = UUID.randomUUID().toString();
        UUID dashboardId = dashboardClient.create(
                dashboard(widgetId, "text_markdown", saveDashboardQuery(WINDOWED_QUERY, projectIdA)),
                API_KEY_A, WORKSPACE_NAME_A);

        assertStatus(dashboardClient.callRunWidgetQuery(dashboardId, widgetId, window(null, null), API_KEY_A,
                WORKSPACE_NAME_A), Response.Status.NOT_FOUND);
        assertStatus(dashboardClient.callRunWidgetQuery(dashboardId, UUID.randomUUID().toString(), window(null, null),
                API_KEY_A, WORKSPACE_NAME_A), Response.Status.NOT_FOUND);
        assertStatus(dashboardClient.callRunWidgetQuery(UUID.randomUUID(), widgetId, window(null, null), API_KEY_A,
                WORKSPACE_NAME_A), Response.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("a dashboard of another workspace is not found")
    void runWidgetQuery__whenDashboardOfAnotherWorkspace__thenNotFound() {
        String widgetId = UUID.randomUUID().toString();
        UUID dashboardIdB = dashboardClient.create(dashboard(widgetId, "ollie_chart", null),
                API_KEY_B, WORKSPACE_NAME_B);

        assertStatus(dashboardClient.callRunWidgetQuery(dashboardIdB, widgetId, window(null, null), API_KEY_A,
                WORKSPACE_NAME_A), Response.Status.NOT_FOUND);
    }

    @Test
    @DisplayName("a workspace not on the Custom Charts allowlist gets 501")
    void runWidgetQuery__whenWorkspaceNotAllowlisted__thenNotImplemented() {
        String widgetId = UUID.randomUUID().toString();
        UUID dashboardIdB = dashboardClient.create(dashboard(widgetId, "ollie_chart", null),
                API_KEY_B, WORKSPACE_NAME_B);

        assertStatus(dashboardClient.callRunWidgetQuery(dashboardIdB, widgetId, window(null, null), API_KEY_B,
                WORKSPACE_NAME_B), Response.Status.NOT_IMPLEMENTED);
    }

    @Test
    @DisplayName("a date range that ends before it starts is rejected")
    void runWidgetQuery__whenRangeReversed__thenBadRequest() {
        String widgetId = UUID.randomUUID().toString();
        UUID dashboardId = dashboardClient.create(
                dashboard(widgetId, "ollie_chart", saveDashboardQuery(WINDOWED_QUERY, projectIdA)),
                API_KEY_A, WORKSPACE_NAME_A);

        assertStatus(dashboardClient.callRunWidgetQuery(dashboardId, widgetId,
                window(traceStartA, traceStartA.minus(Duration.ofHours(1))), API_KEY_A, WORKSPACE_NAME_A),
                Response.Status.BAD_REQUEST);
    }

    static Stream<Arguments> dateRangeBounds() {
        var min = Instant.parse(InRangeValidator.MIN_ANALYTICS_DB);
        var maxExclusive = Instant.parse(InRangeValidator.MAX_ANALYTICS_DB_PRECISION_9);
        return Stream.of(
                arguments("start before the minimum", min.minusNanos(1), null, Response.Status.BAD_REQUEST),
                arguments("end at the exclusive maximum", null, maxExclusive, Response.Status.BAD_REQUEST),
                arguments("end after the maximum", null, maxExclusive.plus(Duration.ofDays(1)),
                        Response.Status.BAD_REQUEST),
                arguments("start at the minimum, end just below the maximum", min, maxExclusive.minusNanos(1),
                        Response.Status.OK));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dateRangeBounds")
    @DisplayName("a date range outside DateTime64(9) is rejected, and its edges are accepted")
    void runWidgetQuery__whenRangeAtBounds__thenStatus(String description, @Nullable Instant start,
            @Nullable Instant end, Response.Status expected) {
        String widgetId = UUID.randomUUID().toString();
        UUID dashboardId = dashboardClient.create(
                dashboard(widgetId, "ollie_chart", saveDashboardQuery(WINDOWED_QUERY, projectIdA)),
                API_KEY_A, WORKSPACE_NAME_A);

        assertStatus(dashboardClient.callRunWidgetQuery(dashboardId, widgetId, window(start, end), API_KEY_A,
                WORKSPACE_NAME_A), expected);
    }

    @ParameterizedTest
    @EnumSource(value = WorkspaceUserPermission.class, names = {"DASHBOARD_VIEW", "PROJECT_DATA_VIEW"})
    @DisplayName("a member without a required permission is refused")
    void runWidgetQuery__whenPermissionDenied__thenForbidden(WorkspaceUserPermission denied) {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), apiKey, workspaceName, denied.getValue());
        String widgetId = UUID.randomUUID().toString();

        assertStatus(dashboardClient.callRunWidgetQuery(UUID.randomUUID(), widgetId, window(null, null), apiKey,
                workspaceName), Response.Status.FORBIDDEN);
        assertStatus(insightsViewClient.callRunWidgetQuery(UUID.randomUUID(), widgetId, window(null, null), apiKey,
                workspaceName), Response.Status.FORBIDDEN);
    }

    @Test
    @DisplayName("a dashboard saved query reads back as it was saved")
    void getDashboardSavedQuery__thenReturnsIt() {
        UUID queryId = saveDashboardQuery(WINDOWED_QUERY, projectIdA);

        try (var response = dashboardSavedQueries().path(queryId.toString()).request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY_A).header(WORKSPACE_HEADER, WORKSPACE_NAME_A).get()) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            var saved = response.readEntity(DashboardSavedQuery.class);
            assertThat(saved.sql()).isEqualTo(WINDOWED_QUERY);
            assertThat(saved.projectId()).isEqualTo(projectIdA);
        }
    }

    @Test
    @DisplayName("a query that is too long is refused, and a workspace off the allowlist cannot save one")
    void saveDashboardQuery__whenTooLongOrNotAllowlisted__thenRefused() {
        assertStatus(
                callSaveDashboardQuery("SELECT 1 -- " + "x".repeat(70_000), null, API_KEY_A, WORKSPACE_NAME_A),
                422);
        assertStatus(callSaveDashboardQuery(WINDOWED_QUERY, null, API_KEY_B, WORKSPACE_NAME_B),
                Response.Status.NOT_IMPLEMENTED);
    }

    @Test
    @DisplayName("a dashboard cannot carry SQL in an Ollie chart, or reference a query that was not saved")
    void createDashboard__whenChartCarriesSqlOrUnknownQuery__thenUnprocessable() {
        String widgetId = UUID.randomUUID().toString();
        JsonNode inlineSql = JsonUtils.valueToTree(Map.of("sections", List.of(Map.of(
                "id", UUID.randomUUID().toString(),
                "widgets", List.of(Map.of("id", widgetId, "type", "ollie_chart",
                        "config", Map.of("query", Map.of("sql", WINDOWED_QUERY))))))));

        assertStatus(dashboardClient.callCreate(createPartialDashboard(DashboardScope.WORKSPACE).config(inlineSql)
                .build(), API_KEY_A, WORKSPACE_NAME_A), 422);
        assertStatus(dashboardClient.callCreate(dashboard(widgetId, "ollie_chart", UUID.randomUUID()), API_KEY_A,
                WORKSPACE_NAME_A), 422);
    }

    private static Dashboard dashboard(String widgetId, String type, @Nullable UUID queryId) {
        Map<String, Object> config = queryId == null ? Map.of() : Map.of("query", Map.of("id", queryId.toString()));
        JsonNode sections = JsonUtils.valueToTree(Map.of("sections", List.of(Map.of(
                "id", UUID.randomUUID().toString(),
                "widgets", List.of(Map.of("id", widgetId, "type", type, "config", config))))));
        return createPartialDashboard(DashboardScope.WORKSPACE).config(sections).build();
    }

    private UUID saveDashboardQuery(String sql, @Nullable UUID projectId) {
        try (var response = callSaveDashboardQuery(sql, projectId, API_KEY_A, WORKSPACE_NAME_A)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.CREATED.getStatusCode());
            return response.readEntity(DashboardSavedQuery.class).id();
        }
    }

    private Response callSaveDashboardQuery(String sql, @Nullable UUID projectId, String apiKey,
            String workspaceName) {
        return dashboardSavedQueries().request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(DashboardSavedQuery.builder().sql(sql).projectId(projectId).build()));
    }

    private void mockDashboardSavedQueriesAuth(String apiKey, String workspaceName, String workspaceId) {
        // The default AuthTestUtils stub only matches /v1/private/*; dashboard saved queries authenticate
        // under /v1/internal/*.
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/auth"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(apiKey))
                .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)))
                .withRequestBody(
                        matchingJsonPath("$.path", matching("/v1/internal/dashboard-saved-queries(/.*)?")))
                .willReturn(okJson(AuthTestUtils.newWorkspaceAuthResponse(USER, workspaceId, workspaceName, null))));
    }

    private WebTarget dashboardSavedQueries() {
        return client.target("%s/v1/internal/dashboard-saved-queries".formatted(baseURI));
    }

    private static DashboardWidgetQueryRequest window(@Nullable Instant start, @Nullable Instant end) {
        return DashboardWidgetQueryRequest.builder().intervalStart(start).intervalEnd(end).build();
    }

    private static AnalyticsQueryResponse run(Response response) {
        try (response) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            return response.readEntity(AnalyticsQueryResponse.class);
        }
    }

    private static void assertStatus(Response response, Response.Status expected) {
        assertStatus(response, expected.getStatusCode());
    }

    private static void assertStatus(Response response, int expected) {
        try (response) {
            assertThat(response.getStatus()).isEqualTo(expected);
        }
    }

    private UUID createTrace(String projectName, Instant startTime, String apiKey, String workspaceName) {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .startTime(startTime)
                .endTime(startTime.plusMillis(100))
                .build();
        return traceResourceClient.createTrace(trace, apiKey, workspaceName);
    }
}
