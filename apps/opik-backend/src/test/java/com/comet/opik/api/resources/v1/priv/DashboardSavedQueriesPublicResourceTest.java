package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DashboardSavedQuery;
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
import com.comet.opik.domain.FreeFormSqlConcurrencyLimiter;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static com.comet.opik.domain.FreeFormSqlQueryService.ACCESS_DENIED_MESSAGE;
import static com.comet.opik.domain.FreeFormSqlQueryService.PARSE_ERROR_MESSAGE;
import static com.comet.opik.domain.FreeFormSqlQueryService.SETTINGS_CLAUSE_MESSAGE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Public Dashboard Saved Queries Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DashboardSavedQueriesPublicResourceTest {

    private static final String USER = UUID.randomUUID().toString();

    private static final String API_KEY_A = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_A = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_A = UUID.randomUUID().toString();

    private static final String API_KEY_B = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_B = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_B = UUID.randomUUID().toString();

    // Only the concurrency test uses this workspace, so the slots it takes block no other test.
    private static final String API_KEY_C = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_C = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_C = UUID.randomUUID().toString();

    private static final int MAX_CONCURRENT_QUERIES = 2;

    private static final String WINDOWED_QUERY = "SELECT toJSONString(map('id', toString(id))) AS result FROM traces"
            + " WHERE start_time >= {{window_start}} AND start_time <= {{window_end}}";

    // Per workspace, shared by save and run. Above what workspace A spends across this class, so only the rate-limit
    // test reaches it.
    private static final int RATE_LIMIT = 20;
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
                        .customLimits(Map.of("analyticsQueries", new LimitConfig("Analytics-Queries",
                                "analytics_queries", RATE_LIMIT, 60, RATE_LIMIT_MESSAGE)))
                        .customConfigs(List.of(
                                new CustomConfig("serviceToggles.ollieEnabled", "true"),
                                new CustomConfig("customCharts.enabled", "true"),
                                new CustomConfig("customCharts.publicEndpointsEnabled", "true"),
                                new CustomConfig("customCharts.maxConcurrentQueriesPerWorkspace",
                                        String.valueOf(MAX_CONCURRENT_QUERIES))))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ClientSupport client;
    private String baseURI;
    private AnalyticsQueriesClient analyticsQueriesClient;
    private LockService lockService;

    private UUID projectIdA;

    @BeforeAll
    void setUpAll(ClientSupport client, LockService lockService) {
        this.client = client;
        this.lockService = lockService;
        baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);
        analyticsQueriesClient = new AnalyticsQueriesClient(client, baseURI);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY_A, WORKSPACE_NAME_A, WORKSPACE_ID_A, USER);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY_B, WORKSPACE_NAME_B, WORKSPACE_ID_B, USER);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY_C, WORKSPACE_NAME_C, WORKSPACE_ID_C, USER);

        projectIdA = new ProjectResourceClient(client, baseURI, factory)
                .createProject(UUID.randomUUID().toString(), API_KEY_A, WORKSPACE_NAME_A);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("a query with date range placeholders is saved and reads back as it was saved")
    void saveDashboardQuery__whenValid__thenGetReturnsIt() {
        Instant before = Instant.now();
        UUID queryId;
        try (var response = callSave(WINDOWED_QUERY, projectIdA, API_KEY_A, WORKSPACE_NAME_A)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.CREATED.getStatusCode());
            queryId = response.readEntity(DashboardSavedQuery.class).id();
            assertThat(response.getLocation().getPath()).endsWith("/v1/private/dashboard-saved-queries/" + queryId);
        }

        try (var response = callGet(queryId, API_KEY_A, WORKSPACE_NAME_A)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            var saved = response.readEntity(DashboardSavedQuery.class);
            assertThat(saved).usingRecursiveComparison().ignoringFields("createdAt")
                    .isEqualTo(DashboardSavedQuery.builder().id(queryId).projectId(projectIdA).sql(WINDOWED_QUERY)
                            .createdBy(USER).build());
            // MySQL stamps created_at by its own clock; a second either side absorbs drift from the test's.
            assertThat(saved.createdAt()).isBetween(before.minus(1, ChronoUnit.SECONDS),
                    Instant.now().plus(1, ChronoUnit.SECONDS));
        }
    }

    static Stream<Arguments> rejectedQueries() {
        return Stream.of(
                arguments("SETTINGS clause in a subquery",
                        "SELECT toJSONString(map('id', toString(id))) AS result FROM (SELECT id FROM traces SETTINGS max_threads = 1)",
                        SETTINGS_CLAUSE_MESSAGE),
                arguments("unparseable query", "SELECT FROM WHERE", PARSE_ERROR_MESSAGE),
                arguments("statement stacking", "SELECT 1 AS result; SELECT 2 AS result", PARSE_ERROR_MESSAGE),
                arguments("placeholder other than the date range",
                        "SELECT toJSONString(map('id', toString(id))) AS result FROM traces WHERE name = {{name}}",
                        PARSE_ERROR_MESSAGE),
                arguments("non-granted table",
                        "SELECT toJSONString(map('n', toString(count()))) AS result FROM span_weeks",
                        ACCESS_DENIED_MESSAGE),
                arguments("system table (outside row policies)",
                        "SELECT toJSONString(map('q', query)) AS result FROM system.query_log",
                        ACCESS_DENIED_MESSAGE),
                arguments("table function",
                        "SELECT toJSONString(map('n', toString(count()))) AS result FROM numbers(10)",
                        ACCESS_DENIED_MESSAGE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedQueries")
    @DisplayName("SQL a run would reject is refused with the run's 400 and message")
    void saveDashboardQuery__whenRunWouldReject__thenBadRequest(String description, String sql, String message) {
        try (var response = callSave(sql, projectIdA, API_KEY_A, WORKSPACE_NAME_A)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
            assertThat(response.readEntity(ErrorMessage.class).errors()).containsExactly(message);
        }
    }

    @Test
    @DisplayName("a scalar subquery reading a table is refused with the rewrite to apply")
    void saveDashboardQuery__whenScalarRead__thenBadRequest() {
        var sql = "SELECT toJSONString(map('n', toString((SELECT count() FROM spans)))) AS result";

        try (var response = callSave(sql, null, API_KEY_A, WORKSPACE_NAME_A)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
            assertThat(response.readEntity(ErrorMessage.class).errors()).singleElement().asString()
                    .startsWith("Query rejected: a scalar subquery").contains("reads spans");
        }
    }

    @Test
    @DisplayName("a query that is too long fails request validation with 422")
    void saveDashboardQuery__whenTooLong__thenUnprocessable() {
        var sql = WINDOWED_QUERY + " ".repeat(DashboardSavedQuery.MAX_SQL_LENGTH);

        try (var response = callSave(sql, null, API_KEY_A, WORKSPACE_NAME_A)) {
            assertThat(response.getStatus()).isEqualTo(422);
        }
    }

    @Test
    @DisplayName("a saved query of another workspace is not found")
    void getDashboardQuery__whenOtherWorkspace__thenNotFound() {
        UUID queryId;
        try (var response = callSave(WINDOWED_QUERY, null, API_KEY_A, WORKSPACE_NAME_A)) {
            queryId = response.readEntity(DashboardSavedQuery.class).id();
        }

        try (var response = callGet(queryId, API_KEY_B, WORKSPACE_NAME_B)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
        }
    }

    @Test
    @DisplayName("a caller without dashboard create is refused saving with 403")
    void saveDashboardQuery__whenDashboardCreateDenied__thenForbidden() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), apiKey, workspaceName,
                WorkspaceUserPermission.DASHBOARD_CREATE.getValue());

        try (var response = callSave(WINDOWED_QUERY, null, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.FORBIDDEN.getStatusCode());
        }
    }

    @Test
    @DisplayName("a caller without dashboard view is refused reading with 403")
    void getDashboardQuery__whenDashboardViewDenied__thenForbidden() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), apiKey, workspaceName,
                WorkspaceUserPermission.DASHBOARD_VIEW.getValue());

        try (var response = callGet(UUID.randomUUID(), apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.FORBIDDEN.getStatusCode());
        }
    }

    @Test
    @DisplayName("saves spend the run endpoint's per-workspace budget: once saves use it up, a run gets 429")
    void saveDashboardQuery__whenAnalyticsQueriesBudgetSpent__thenSaveAndRunTooManyRequests() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspaceWithPermissions(wireMock.server(), apiKey, workspaceName,
                UUID.randomUUID().toString(), USER, List.of(WorkspaceUserPermission.ORIGINAL_DATA_VIEW.getValue()));

        for (int i = 0; i < RATE_LIMIT; i++) {
            try (var response = callSave(WINDOWED_QUERY, null, apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(Response.Status.CREATED.getStatusCode());
            }
        }
        try (var response = callSave(WINDOWED_QUERY, null, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.TOO_MANY_REQUESTS.getStatusCode());
            assertThat(response.readEntity(String.class)).contains(RATE_LIMIT_MESSAGE);
        }
        try (var response = analyticsQueriesClient.callRun(null,
                "SELECT toJSONString(map('id', toString(id))) AS result FROM traces", apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.TOO_MANY_REQUESTS.getStatusCode());
            assertThat(response.readEntity(String.class)).contains(RATE_LIMIT_MESSAGE);
        }
    }

    @Test
    @DisplayName("with every workspace slot taken, save returns 429 with Retry-After, and saves once a slot is freed")
    void saveDashboardQuery__whenWorkspaceSlotsTaken__thenTooManyRequestsUntilOneFrees() {
        var lock = FreeFormSqlConcurrencyLimiter.slotLock(WORKSPACE_ID_C, MAX_CONCURRENT_QUERIES);
        // Longer than the test can run, so only the release below can free a slot.
        var permits = IntStream.range(0, MAX_CONCURRENT_QUERIES)
                .mapToObj(i -> lockService.tryAcquireSlot(lock, MAX_CONCURRENT_QUERIES, Duration.ofMinutes(10)).block())
                .toList();
        assertThat(permits).doesNotContainNull();

        try (var response = callSave(WINDOWED_QUERY, null, API_KEY_C, WORKSPACE_NAME_C)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.TOO_MANY_REQUESTS.getStatusCode());
            assertThat(response.getHeaderString(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(response.readEntity(ErrorMessage.class))
                    .isEqualTo(new ErrorMessage(List.of(FreeFormSqlConcurrencyLimiter.TOO_MANY_QUERIES_MESSAGE)));
        }

        lockService.releaseSlot(lock, permits.getFirst()).block();

        try (var response = callSave(WINDOWED_QUERY, null, API_KEY_C, WORKSPACE_NAME_C)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.CREATED.getStatusCode());
        }
        lockService.releaseSlot(lock, permits.getLast()).block();
    }

    private Response callSave(String sql, @Nullable UUID projectId, String apiKey, String workspaceName) {
        return savedQueries().request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(DashboardSavedQuery.builder().sql(sql).projectId(projectId).build()));
    }

    private Response callGet(UUID id, String apiKey, String workspaceName) {
        return savedQueries().path(id.toString()).request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    private WebTarget savedQueries() {
        return client.target("%s/v1/private/dashboard-saved-queries".formatted(baseURI));
    }
}
