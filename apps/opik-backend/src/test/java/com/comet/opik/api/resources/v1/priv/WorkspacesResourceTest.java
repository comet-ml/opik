package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.Trace;
import com.comet.opik.api.WorkspaceConfiguration;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.metrics.BreakdownConfig;
import com.comet.opik.api.metrics.BreakdownField;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.comet.opik.api.metrics.WorkspaceMetricRequest;
import com.comet.opik.api.metrics.WorkspaceMetricResponse;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryRequest;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.metrics.WorkspaceSpanMetricRequest;
import com.comet.opik.api.metrics.WorkspaceTokenUsageNamesRequest;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectMetricsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.resources.WorkspaceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Workspace Metrics Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class WorkspacesResourceTest {
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = RandomStringUtils.randomAlphabetic(10);

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private WorkspaceResourceClient workspaceResourceClient;
    private ProjectMetricsResourceClient projectMetricsResourceClient;
    private IdGenerator idGenerator;

    @BeforeAll
    void setUpAll(ClientSupport client, IdGenerator idGenerator) throws SQLException {

        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.workspaceResourceClient = new WorkspaceResourceClient(client, baseURI, factory);
        this.projectMetricsResourceClient = new ProjectMetricsResourceClient(client, baseURI);
        this.idGenerator = idGenerator;

        ClientSupportUtils.config(client);

        mockTargetWorkspace();
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace() {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @Nested
    @DisplayName("Required permissions")
    class RequiredPermissionsTest {

        @Test
        @DisplayName("Upsert workspace configuration passes required permissions to auth endpoint")
        void upsertWorkspaceConfigurationPassesRequiredPermissionsToAuthEndpoint() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var configuration = WorkspaceConfiguration.builder()
                    .timeoutToMarkThreadAsInactive(Duration.ofHours(1))
                    .build();

            wireMock.server().resetRequests();
            workspaceResourceClient.callUpsertWorkspaceConfiguration(configuration, apiKey, workspaceName).close();

            wireMock.server().verify(
                    postRequestedFor(urlPathEqualTo("/opik/auth"))
                            .withRequestBody(matchingJsonPath("$.requiredPermissions[0]",
                                    equalTo(WorkspaceUserPermission.WORKSPACE_SETTINGS_CONFIGURE.getValue()))));
        }

        @Test
        @DisplayName("Delete workspace configuration passes required permissions to auth endpoint")
        void deleteWorkspaceConfigurationPassesRequiredPermissionsToAuthEndpoint() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            wireMock.server().resetRequests();
            workspaceResourceClient.callDeleteWorkspaceConfiguration(apiKey, workspaceName).close();

            wireMock.server().verify(
                    postRequestedFor(urlPathEqualTo("/opik/auth"))
                            .withRequestBody(matchingJsonPath("$.requiredPermissions[0]",
                                    equalTo(WorkspaceUserPermission.WORKSPACE_SETTINGS_CONFIGURE.getValue()))));
        }

        @Test
        @DisplayName("Upsert workspace configuration returns 403 when permission is denied")
        void upsertWorkspaceConfigurationReturnsForbiddenWhenPermissionDenied() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();

            AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), apiKey, workspaceName,
                    WorkspaceUserPermission.WORKSPACE_SETTINGS_CONFIGURE.getValue());

            var configuration = WorkspaceConfiguration.builder()
                    .timeoutToMarkThreadAsInactive(Duration.ofHours(1))
                    .build();

            try (var response = workspaceResourceClient.callUpsertWorkspaceConfiguration(configuration, apiKey,
                    workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            }
        }

        @Test
        @DisplayName("Delete workspace configuration returns 403 when permission is denied")
        void deleteWorkspaceConfigurationReturnsForbiddenWhenPermissionDenied() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();

            AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock.server(), apiKey, workspaceName,
                    WorkspaceUserPermission.WORKSPACE_SETTINGS_CONFIGURE.getValue());

            try (var response = workspaceResourceClient.callDeleteWorkspaceConfiguration(apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
            }
        }
    }

    @Nested
    @DisplayName("Feedback scores metrics")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FeedbackScoresMetricsTest {

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @Disabled
        void metricsSummary_happyPath(boolean withProjectIds) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            Instant startTime = Instant.now().minus(Duration.ofMinutes(10));
            Instant endTime = Instant.now();

            var previousScores = createFeedbackScores(projectName, names.subList(0, names.size() - 1), apiKey,
                    workspaceName, startTime.minus(Duration.ofMinutes(5)));

            var currentScores = createFeedbackScores(projectName, names.subList(1, names.size()), apiKey,
                    workspaceName, startTime.plus(Duration.ofMinutes(5)));

            // Get metrics summary
            var actualMetricsSummary = workspaceResourceClient.getMetricsSummary(
                    WorkspaceMetricsSummaryRequest.builder()
                            .intervalStart(startTime)
                            .intervalEnd(endTime)
                            .projectIds(withProjectIds ? Set.of(projectId) : null)
                            .build(),
                    apiKey, workspaceName);

            var expectedMetricsSummary = prepareMetricsSummary(previousScores, currentScores);

            assertThat(actualMetricsSummary.results())
                    .usingRecursiveComparison()
                    .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "current", "previous")
                    .ignoringCollectionOrder()
                    .isEqualTo(expectedMetricsSummary);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void metricsSummary_emptyData(boolean withProjectIds) {
            var projectId = UUID.randomUUID();

            var startTime = Instant.now();
            Instant endTime = startTime.plus(Duration.ofMinutes(10));

            // Get metrics summary
            var actualMetricsSummary = workspaceResourceClient.getMetricsSummary(
                    WorkspaceMetricsSummaryRequest.builder()
                            .intervalStart(startTime)
                            .intervalEnd(endTime)
                            .projectIds(withProjectIds ? Set.of(projectId) : null)
                            .build(),
                    API_KEY, WORKSPACE_NAME);

            assertThat(actualMetricsSummary.results()).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @Disabled
        void metricsDaily_happyPath(boolean withProjectIds) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String projectName = RandomStringUtils.randomAlphabetic(10);
            String projectName2 = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
            var projectId2 = projectResourceClient.createProject(projectName2, apiKey, workspaceName);

            String name = UUID.randomUUID().toString();

            var project1Scores = createFeedbackScores(projectName, List.of(name), apiKey,
                    workspaceName);
            var project2Scores = createFeedbackScores(projectName2, List.of(name), apiKey,
                    workspaceName);

            var endTime = Instant.now();
            var startTime = endTime.minus(Duration.ofDays(1));

            // Get metrics summary
            var actualMetrics = workspaceResourceClient.getMetricsDaily(
                    WorkspaceMetricRequest.builder()
                            .intervalStart(startTime)
                            .intervalEnd(endTime)
                            .projectIds(withProjectIds ? Set.of(projectId, projectId2) : null)
                            .name(name)
                            .build(),
                    apiKey, workspaceName);

            var expectedMetricsDaily = withProjectIds
                    ? prepareMetricsDailyPerProjects(project1Scores.values(), project2Scores.values(), name, projectId,
                            projectId2, WorkspacesResourceTest.this::getAvg)
                    : prepareMetricsDailyPerWorkspace(project1Scores.values(), project2Scores.values(), name,
                            WorkspacesResourceTest.this::getAvg);

            assertThat(actualMetrics.results())
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .withComparatorForType(StatsUtils::closeToEpsilonComparator, Double.class)
                    .isEqualTo(expectedMetricsDaily);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void metricsDaily_emptyData(boolean withProjectIds) {
            var projectId = UUID.randomUUID();
            String name = UUID.randomUUID().toString();

            var endTime = Instant.now();
            var startTime = endTime.minus(Duration.ofDays(1));

            var actualMetrics = workspaceResourceClient.getMetricsDaily(
                    WorkspaceMetricRequest.builder()
                            .intervalStart(startTime)
                            .intervalEnd(endTime)
                            .projectIds(withProjectIds ? Set.of(projectId) : null)
                            .name(name)
                            .build(),
                    API_KEY, WORKSPACE_NAME);

            if (withProjectIds) {
                assertThat(actualMetrics.results()).isEmpty();
            } else {
                var expectedMetricsSummary = prepareMetricsDailyPerWorkspace(List.of(), List.of(), name,
                        WorkspacesResourceTest.this::getAvg);

                assertThat(actualMetrics.results())
                        .usingRecursiveComparison()
                        .ignoringCollectionOrder()
                        .isEqualTo(expectedMetricsSummary);
            }
        }
    }

    @Nested
    @DisplayName("Costs metrics")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CostsMetricsTest {

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void costsSummary_happyPath(boolean withProjectIds) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

            Instant startTime = Instant.now().minus(Duration.ofMinutes(10));
            Instant endTime = Instant.now();

            var previousSpans = createSpans(projectName, apiKey, workspaceName, startTime.minus(Duration.ofMinutes(5)));
            var currentSpans = createSpans(projectName, apiKey, workspaceName, startTime.plus(Duration.ofMinutes(5)));

            // Get costs summary
            var actualCostsSummary = workspaceResourceClient.getCostsSummary(
                    WorkspaceMetricsSummaryRequest.builder()
                            .intervalStart(startTime)
                            .intervalEnd(endTime)
                            .projectIds(withProjectIds ? Set.of(projectId) : null)
                            .build(),
                    apiKey, workspaceName);

            var expectedCostsSummary = prepareCostsSummary(previousSpans, currentSpans);

            assertThat(actualCostsSummary)
                    .usingComparatorForFields(StatsUtils::closeToEpsilonComparator, "current", "previous")
                    .isEqualTo(expectedCostsSummary);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void costsSummary_emptyData(boolean withProjectIds) {
            var projectId = UUID.randomUUID();

            var startTime = Instant.now();
            Instant endTime = startTime.plus(Duration.ofMinutes(10));

            // Get metrics summary
            var actualCostsSummary = workspaceResourceClient.getCostsSummary(
                    WorkspaceMetricsSummaryRequest.builder()
                            .intervalStart(startTime)
                            .intervalEnd(endTime)
                            .projectIds(withProjectIds ? Set.of(projectId) : null)
                            .build(),
                    API_KEY, WORKSPACE_NAME);

            var expectedCostsSummary = WorkspaceMetricsSummaryResponse.Result.builder()
                    .name("cost")
                    .current(0D)
                    .previous(0D)
                    .build();

            assertThat(actualCostsSummary).isEqualTo(expectedCostsSummary);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void costsDaily_happyPath(boolean withProjectIds) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String projectName = RandomStringUtils.randomAlphabetic(10);
            String projectName2 = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
            var projectId2 = projectResourceClient.createProject(projectName2, apiKey, workspaceName);

            var project1Spans = createSpans(projectName, apiKey, workspaceName, Instant.now());
            var project2Spans = createSpans(projectName2, apiKey, workspaceName, Instant.now());

            var endTime = Instant.now();
            var startTime = endTime.minus(Duration.ofDays(1));

            // Get metrics summary
            var actualMetrics = workspaceResourceClient.getCostsDaily(
                    WorkspaceMetricRequest.builder()
                            .intervalStart(startTime)
                            .intervalEnd(endTime)
                            .projectIds(withProjectIds ? Set.of(projectId, projectId2) : null)
                            .build(),
                    apiKey, workspaceName);

            var expectedCostDaily = withProjectIds
                    ? prepareMetricsDailyPerProjects(mapToCost(project1Spans), mapToCost(project2Spans), "cost",
                            projectId, projectId2, WorkspacesResourceTest.this::getSum)
                    : prepareMetricsDailyPerWorkspace(mapToCost(project1Spans), mapToCost(project2Spans), "cost",
                            WorkspacesResourceTest.this::getSum);

            assertThat(actualMetrics.results())
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .withComparatorForType(StatsUtils::closeToEpsilonComparator, Double.class)
                    .isEqualTo(expectedCostDaily);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void costsDaily_emptyData(boolean withProjectIds) {
            var projectId = UUID.randomUUID();
            String name = "cost";

            var endTime = Instant.now();
            var startTime = endTime.minus(Duration.ofDays(1));

            var actualMetrics = workspaceResourceClient.getCostsDaily(
                    WorkspaceMetricRequest.builder()
                            .intervalStart(startTime)
                            .intervalEnd(endTime)
                            .projectIds(withProjectIds ? Set.of(projectId) : null)
                            .name(name)
                            .build(),
                    API_KEY, WORKSPACE_NAME);

            if (withProjectIds) {
                assertThat(actualMetrics.results()).isEmpty();
            } else {
                var expectedMetricsSummary = prepareMetricsDailyPerWorkspace(List.of(), List.of(), name,
                        WorkspacesResourceTest.this::getSum);

                assertThat(actualMetrics.results())
                        .usingRecursiveComparison()
                        .ignoringCollectionOrder()
                        .isEqualTo(expectedMetricsSummary);
            }
        }
    }

    @Nested
    @DisplayName("Span metrics")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SpanMetricsTest {

        @Test
        void spanTokenUsage_matchesPerProjectEndpoint() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

            createSpansWithUsage(projectName, apiKey, workspaceName, Instant.now(), "completion_tokens",
                    List.of("openai", "anthropic", "openai"));

            var endTime = Instant.now();
            var startTime = endTime.minus(Duration.ofDays(1));

            var perProject = projectMetricsResourceClient.getProjectMetrics(projectId,
                    ProjectMetricRequest.builder()
                            .metricType(MetricType.SPAN_TOKEN_USAGE)
                            .interval(TimeInterval.HOURLY)
                            .intervalStart(startTime)
                            .intervalEnd(endTime)
                            .build(),
                    Number.class, apiKey, workspaceName);

            // Explicit project subset must match the per-project endpoint exactly (the "exact copy" contract)
            var workspaceSubset = workspaceResourceClient.getWorkspaceSpanMetric(
                    spanRequest(MetricType.SPAN_TOKEN_USAGE, Set.of(projectId), startTime, endTime, null), apiKey,
                    workspaceName);
            assertSeriesMatch(seriesFromWorkspace(workspaceSubset.results()),
                    seriesFromProject(perProject.results()));

            // No project ids => all projects. Workspace has only this project, so it must also match.
            var workspaceAll = workspaceResourceClient.getWorkspaceSpanMetric(
                    spanRequest(MetricType.SPAN_TOKEN_USAGE, null, startTime, endTime, null), apiKey, workspaceName);
            assertSeriesMatch(seriesFromWorkspace(workspaceAll.results()), seriesFromProject(perProject.results()));
        }

        @Test
        void spanTokenUsage_withProviderBreakdown_matchesPerProjectEndpoint() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

            // Seed spans with a known usage key and providers so the token-usage breakdown is deterministic
            createSpansWithUsage(projectName, apiKey, workspaceName, Instant.now(), "completion_tokens",
                    List.of("openai", "anthropic", "openai"));

            var endTime = Instant.now();
            var startTime = endTime.minus(Duration.ofDays(1));
            var breakdown = BreakdownConfig.builder()
                    .field(BreakdownField.PROVIDER)
                    .subMetric("completion_tokens")
                    .build();

            var perProject = projectMetricsResourceClient.getProjectMetrics(projectId,
                    ProjectMetricRequest.builder()
                            .metricType(MetricType.SPAN_TOKEN_USAGE)
                            .interval(TimeInterval.HOURLY)
                            .intervalStart(startTime)
                            .intervalEnd(endTime)
                            .breakdown(breakdown)
                            .build(),
                    Number.class, apiKey, workspaceName);

            var workspace = workspaceResourceClient.getWorkspaceSpanMetric(
                    spanRequest(MetricType.SPAN_TOKEN_USAGE, Set.of(projectId), startTime, endTime, breakdown),
                    apiKey, workspaceName);

            assertThat(workspace.results()).isNotEmpty();
            assertSeriesMatch(seriesFromWorkspace(workspace.results()), seriesFromProject(perProject.results()));
        }

        @Test
        void allProjects_matchSelectedProjects_whenWorkspaceContainsOnlyThose() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

            String projectName1 = RandomStringUtils.randomAlphabetic(10);
            String projectName2 = RandomStringUtils.randomAlphabetic(10);
            var projectId1 = projectResourceClient.createProject(projectName1, apiKey, workspaceName);
            var projectId2 = projectResourceClient.createProject(projectName2, apiKey, workspaceName);

            createSpansWithUsage(projectName1, apiKey, workspaceName, Instant.now(), "completion_tokens",
                    List.of("openai", "anthropic"));
            createSpansWithUsage(projectName2, apiKey, workspaceName, Instant.now(), "completion_tokens",
                    List.of("openai"));

            var endTime = Instant.now();
            var startTime = endTime.minus(Duration.ofDays(1));

            var all = workspaceResourceClient.getWorkspaceSpanMetric(
                    spanRequest(MetricType.SPAN_TOKEN_USAGE, null, startTime, endTime, null), apiKey, workspaceName);
            var selected = workspaceResourceClient.getWorkspaceSpanMetric(
                    spanRequest(MetricType.SPAN_TOKEN_USAGE, Set.of(projectId1, projectId2), startTime, endTime, null),
                    apiKey, workspaceName);

            assertThat(sumValues(all.results())).isPositive();
            assertSeriesMatch(seriesFromWorkspace(all.results()), seriesFromWorkspace(selected.results()));
        }

        @Test
        void allProjects_returnsEmpty_whenWorkspaceHasNoData() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

            var endTime = Instant.now();
            var startTime = endTime.minus(Duration.ofDays(1));

            // "All projects" resolves the project set server-side; with no span data it must return nothing rather
            // than falling back to an unconstrained workspace-wide scan.
            var all = workspaceResourceClient.getWorkspaceSpanMetric(
                    spanRequest(MetricType.SPAN_TOKEN_USAGE, null, startTime, endTime, null), apiKey, workspaceName);

            assertThat(sumValues(all.results())).isZero();
        }

        @Test
        void workspaceMetric_isolatesByWorkspace() {
            var workspaceA = UUID.randomUUID().toString();
            var apiKeyA = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKeyA, workspaceA, UUID.randomUUID().toString());
            var workspaceB = UUID.randomUUID().toString();
            var apiKeyB = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKeyB, workspaceB, UUID.randomUUID().toString());

            String projectName = RandomStringUtils.randomAlphabetic(10);
            projectResourceClient.createProject(projectName, apiKeyA, workspaceA);
            createSpansWithUsage(projectName, apiKeyA, workspaceA, Instant.now(), "completion_tokens",
                    List.of("openai", "anthropic"));

            var endTime = Instant.now();
            var startTime = endTime.minus(Duration.ofDays(1));
            var request = spanRequest(MetricType.SPAN_TOKEN_USAGE, null, startTime, endTime, null);

            assertThat(
                    sumValues(workspaceResourceClient.getWorkspaceSpanMetric(request, apiKeyA, workspaceA).results()))
                    .isPositive();
            assertThat(
                    sumValues(workspaceResourceClient.getWorkspaceSpanMetric(request, apiKeyB, workspaceB).results()))
                    .isZero();
        }

        @Test
        void spanTokenUsage_withFilter_matchesPerProjectEndpoint() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

            // Two openai spans and one anthropic span, each with 10 completion tokens, so a provider filter must
            // narrow the total to the two openai spans => 20 tokens.
            createSpansWithUsage(projectName, apiKey, workspaceName, Instant.now(), "completion_tokens",
                    List.of("openai", "anthropic", "openai"));

            var endTime = Instant.now();
            var startTime = endTime.minus(Duration.ofDays(1));
            List<SpanFilter> filters = List.of(SpanFilter.builder()
                    .field(SpanField.PROVIDER)
                    .operator(Operator.EQUAL)
                    .value("openai")
                    .build());

            var perProject = projectMetricsResourceClient.getProjectMetrics(projectId,
                    ProjectMetricRequest.builder()
                            .metricType(MetricType.SPAN_TOKEN_USAGE)
                            .interval(TimeInterval.HOURLY)
                            .intervalStart(startTime)
                            .intervalEnd(endTime)
                            .spanFilters(filters)
                            .build(),
                    Number.class, apiKey, workspaceName);

            var workspace = workspaceResourceClient.getWorkspaceSpanMetric(
                    WorkspaceSpanMetricRequest.builder()
                            .metricType(MetricType.SPAN_TOKEN_USAGE)
                            .interval(TimeInterval.HOURLY)
                            .intervalStart(startTime)
                            .intervalEnd(endTime)
                            .projectIds(Set.of(projectId))
                            .filters(filters)
                            .build(),
                    apiKey, workspaceName);

            assertThat(sumValues(workspace.results())).isEqualTo(20d);
            assertSeriesMatch(seriesFromWorkspace(workspace.results()), seriesFromProject(perProject.results()));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void tokenUsageBreakdown_withoutSubMetric_returnsBadRequest(String subMetric) {
            var startTime = Instant.now().minus(Duration.ofDays(1));
            var breakdown = BreakdownConfig.builder().field(BreakdownField.PROVIDER).subMetric(subMetric).build();
            var request = spanRequest(MetricType.SPAN_TOKEN_USAGE, null, startTime, Instant.now(), breakdown);

            try (var response = workspaceResourceClient.callGetWorkspaceSpanMetric(request, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }
        }

        // Only SPAN_TOKEN_USAGE is supported across projects; everything else (including other span metrics) is 400.
        @ParameterizedTest
        @EnumSource(value = MetricType.class, names = {"SPAN_COUNT", "SPAN_COST", "SPAN_DURATION", "TRACE_COUNT"})
        void unsupportedMetricType_returnsBadRequest(MetricType metricType) {
            var startTime = Instant.now().minus(Duration.ofDays(1));
            var request = spanRequest(metricType, null, startTime, Instant.now(), null);

            try (var response = workspaceResourceClient.callGetWorkspaceSpanMetric(request, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }
        }

        @Test
        void missingMetricType_returnsBadRequest() {
            var startTime = Instant.now().minus(Duration.ofDays(1));
            var request = spanRequest(null, null, startTime, Instant.now(), null);

            try (var response = workspaceResourceClient.callGetWorkspaceSpanMetric(request, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }
        }
    }

    @Nested
    @DisplayName("Token usage names")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TokenUsageNamesTest {

        @Test
        void matchesPerProjectEndpoint() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
            createSpansWithUsage(projectName, apiKey, workspaceName, Instant.now(), "completion_tokens",
                    List.of("openai"));

            var perProject = projectResourceClient.findTokenUsageNames(projectId, apiKey, workspaceName);

            // Explicit project subset must match the per-project endpoint exactly.
            var workspaceSubset = workspaceResourceClient.getWorkspaceTokenUsageNames(
                    namesRequest(Set.of(projectId)), apiKey, workspaceName);
            assertThat(workspaceSubset.names()).containsExactlyInAnyOrderElementsOf(perProject.names());
        }

        // "All projects" is signalled by an absent (null) or explicit empty project_ids; both resolve to every project
        // in the workspace. Parameterized so the empty-array path can't regress in WorkspaceMetricsService.resolveProjectIds.
        @ParameterizedTest
        @NullAndEmptySource
        void allProjectsMatchesPerProjectEndpoint(Set<UUID> allProjectsSelector) {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
            createSpansWithUsage(projectName, apiKey, workspaceName, Instant.now(), "completion_tokens",
                    List.of("openai"));

            var perProject = projectResourceClient.findTokenUsageNames(projectId, apiKey, workspaceName);

            // Workspace has only this project, so "all projects" must match the per-project endpoint exactly.
            var workspaceAll = workspaceResourceClient.getWorkspaceTokenUsageNames(
                    namesRequest(allProjectsSelector), apiKey, workspaceName);
            assertThat(workspaceAll.names()).containsExactlyInAnyOrderElementsOf(perProject.names());
            assertThat(workspaceAll.names()).isNotEmpty();
        }

        @Test
        void unionsKeysAcrossSelectedProjects() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

            String projectName1 = RandomStringUtils.randomAlphabetic(10);
            String projectName2 = RandomStringUtils.randomAlphabetic(10);
            var projectId1 = projectResourceClient.createProject(projectName1, apiKey, workspaceName);
            var projectId2 = projectResourceClient.createProject(projectName2, apiKey, workspaceName);
            createSpansWithUsage(projectName1, apiKey, workspaceName, Instant.now(), "completion_tokens",
                    List.of("openai"));
            createSpansWithUsage(projectName2, apiKey, workspaceName, Instant.now(), "prompt_tokens",
                    List.of("openai"));

            var result = workspaceResourceClient.getWorkspaceTokenUsageNames(
                    namesRequest(Set.of(projectId1, projectId2)), apiKey, workspaceName);

            assertThat(result.names()).containsExactlyInAnyOrder("completion_tokens", "prompt_tokens");
        }

        // OPIK-7373 regression: a project with no token usage is the FE "representative" for a multi-project selection.
        // The names list must still surface the other selected projects' keys instead of coming back empty.
        @Test
        void includesKeysFromOtherProjects_whenOneSelectedProjectHasNoUsage() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

            String emptyProjectName = RandomStringUtils.randomAlphabetic(10);
            String usageProjectName = RandomStringUtils.randomAlphabetic(10);
            var emptyProjectId = projectResourceClient.createProject(emptyProjectName, apiKey, workspaceName);
            var usageProjectId = projectResourceClient.createProject(usageProjectName, apiKey, workspaceName);
            createSpansWithUsage(usageProjectName, apiKey, workspaceName, Instant.now(), "completion_tokens",
                    List.of("openai"));

            // The empty project alone yields nothing (the exact condition that made the dropdown show "No data").
            assertThat(projectResourceClient.findTokenUsageNames(emptyProjectId, apiKey, workspaceName).names())
                    .isEmpty();

            var result = workspaceResourceClient.getWorkspaceTokenUsageNames(
                    namesRequest(Set.of(emptyProjectId, usageProjectId)), apiKey, workspaceName);

            assertThat(result.names()).containsExactly("completion_tokens");
        }

        @Test
        void returnsEmpty_whenNoUsage() {
            var workspaceName = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

            var result = workspaceResourceClient.getWorkspaceTokenUsageNames(namesRequest(Set.of(projectId)), apiKey,
                    workspaceName);

            assertThat(result.names()).isEmpty();
        }

        @Test
        void namesIsolatedByWorkspace() {
            var workspaceA = UUID.randomUUID().toString();
            var apiKeyA = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKeyA, workspaceA, UUID.randomUUID().toString());
            var workspaceB = UUID.randomUUID().toString();
            var apiKeyB = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKeyB, workspaceB, UUID.randomUUID().toString());

            String projectName = RandomStringUtils.randomAlphabetic(10);
            projectResourceClient.createProject(projectName, apiKeyA, workspaceA);
            createSpansWithUsage(projectName, apiKeyA, workspaceA, Instant.now(), "completion_tokens",
                    List.of("openai"));

            assertThat(workspaceResourceClient.getWorkspaceTokenUsageNames(namesRequest(null), apiKeyA, workspaceA)
                    .names()).containsExactly("completion_tokens");
            assertThat(workspaceResourceClient.getWorkspaceTokenUsageNames(namesRequest(null), apiKeyB, workspaceB)
                    .names()).isEmpty();
        }
    }

    private WorkspaceTokenUsageNamesRequest namesRequest(Set<UUID> projectIds) {
        return WorkspaceTokenUsageNamesRequest.builder().projectIds(projectIds).build();
    }

    private WorkspaceSpanMetricRequest spanRequest(MetricType metricType, Set<UUID> projectIds, Instant startTime,
            Instant endTime, BreakdownConfig breakdown) {
        return WorkspaceSpanMetricRequest.builder()
                .metricType(metricType)
                .interval(TimeInterval.HOURLY)
                .intervalStart(startTime)
                .intervalEnd(endTime)
                .projectIds(projectIds)
                .breakdown(breakdown)
                .build();
    }

    // Compares two sets of series as name -> (bucket -> value) maps, so the WorkspaceMetricResponse shape can be
    // checked against the per-project ProjectMetricResponse (parity) or against another workspace response.
    private void assertSeriesMatch(Map<String, Map<String, Double>> actual,
            Map<String, Map<String, Double>> expected) {
        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        actual.forEach((name, points) -> {
            var expectedPoints = expected.get(name);
            assertThat(points.keySet()).containsExactlyInAnyOrderElementsOf(expectedPoints.keySet());
            points.forEach((bucket, value) -> assertThat(value == null ? 0d : value)
                    .isCloseTo(expectedPoints.get(bucket) == null ? 0d : expectedPoints.get(bucket), within(1e-6)));
        });
    }

    private Map<String, Map<String, Double>> seriesFromWorkspace(List<WorkspaceMetricResponse.Result> results) {
        return results.stream()
                .collect(Collectors.toMap(WorkspaceMetricResponse.Result::name, result -> pointsToMap(result.data())));
    }

    private Map<String, Map<String, Double>> seriesFromProject(List<ProjectMetricResponse.Results<Number>> results) {
        return results.stream()
                .collect(Collectors.toMap(ProjectMetricResponse.Results::name, result -> pointsToMap(result.data())));
    }

    private Map<String, Double> pointsToMap(List<? extends DataPoint<? extends Number>> data) {
        return data.stream().collect(
                HashMap::new,
                (map, point) -> map.put(point.time().toString(),
                        point.value() == null ? null : point.value().doubleValue()),
                HashMap::putAll);
    }

    private double sumValues(List<WorkspaceMetricResponse.Result> results) {
        return results.stream()
                .flatMap(result -> result.data().stream())
                .map(DataPoint::value)
                .filter(value -> value != null)
                .mapToDouble(Number::doubleValue)
                .sum();
    }

    private List<Span> createSpansWithUsage(String projectName, String apiKey, String workspaceName, Instant time,
            String usageKey, List<String> providers) {
        var spans = providers.stream()
                .map(provider -> factory.manufacturePojo(Span.class).toBuilder()
                        .startTime(time)
                        .id(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                        .projectId(null)
                        .projectName(projectName)
                        .provider(provider)
                        .usage(Map.of(usageKey, 10))
                        .feedbackScores(null)
                        .build())
                .toList();

        spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

        return spans;
    }

    private List<Span> createSpans(String projectName, String apiKey,
            String workspaceName, Instant time) {
        var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                .stream()
                .map(span -> span.toBuilder()
                        .startTime(time)
                        .id(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                        .projectId(null)
                        .projectName(projectName)
                        .feedbackScores(null)
                        .build())
                .toList();

        spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

        return spans;
    }

    private Map<String, Double> createFeedbackScores(String projectName, List<String> scoreNames, String apiKey,
            String workspaceName) {
        return createFeedbackScores(projectName, scoreNames, apiKey, workspaceName, null);
    }

    private Map<String, Double> createFeedbackScores(String projectName, List<String> scoreNames, String apiKey,
            String workspaceName, Instant time) {
        return IntStream.range(0, 5)
                .mapToObj(i -> {
                    // create a trace
                    Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                            .id(time == null
                                    ? idGenerator.generateId()
                                    : idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                            .projectName(projectName)
                            .startTime(time == null
                                    ? Instant.now()
                                    : time)
                            .build();

                    traceResourceClient.createTrace(trace, apiKey, workspaceName);

                    // create several feedback scores for that trace
                    List<FeedbackScoreBatchItem> scores = scoreNames.stream()
                            .map(name -> factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                    .name(name)
                                    .projectName(projectName)
                                    .id(trace.id())
                                    .build())
                            .collect(Collectors.toList());

                    traceResourceClient.feedbackScores(scores, apiKey, workspaceName);

                    return scores;
                })
                .flatMap(List::stream)
                .collect(Collectors.groupingBy(FeedbackScoreItem::name))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> calcAverage(e.getValue().stream().map(FeedbackScoreItem::value)
                                .toList())));
    }

    private static Double calcAverage(List<BigDecimal> scores) {
        BigDecimal sum = scores.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(scores.size()), RoundingMode.UP).doubleValue();
    }

    private static Double calcTotalCost(List<Span> spans) {
        return spans.stream()
                .map(Span::totalEstimatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .doubleValue();
    }

    private WorkspaceMetricsSummaryResponse.Result prepareCostsSummary(List<Span> previousSpans,
            List<Span> currentSpans) {
        return WorkspaceMetricsSummaryResponse.Result.builder()
                .name("cost")
                .current(calcTotalCost(currentSpans))
                .previous(calcTotalCost((previousSpans)))
                .build();
    }

    private List<WorkspaceMetricsSummaryResponse.Result> prepareMetricsSummary(Map<String, Double> previousScores,
            Map<String, Double> currentScores) {
        return Stream.concat(previousScores.keySet().stream(), currentScores.keySet().stream())
                .distinct()
                .map(name -> WorkspaceMetricsSummaryResponse.Result.builder()
                        .name(name)
                        .current(currentScores.get(name))
                        .previous(previousScores.get(name))
                        .build())
                .filter(result -> result.current() != null)
                .toList();
    }

    private List<WorkspaceMetricResponse.Result> prepareMetricsDailyPerProjects(Collection<Double> project1Scores,
            Collection<Double> project2Scores,
            String name, UUID projectId1, UUID projectId2, Function<Collection<Double>, Double> aggregator) {
        return List.of(WorkspaceMetricResponse.Result.builder()
                .projectId(projectId1)
                .name(name)
                .data(prepareMetricsAverageDailyData(project1Scores, aggregator))
                .build(),
                WorkspaceMetricResponse.Result.builder()
                        .projectId(projectId2)
                        .name(name)
                        .data(prepareMetricsAverageDailyData(project2Scores, aggregator))
                        .build());
    }

    private List<WorkspaceMetricResponse.Result> prepareMetricsDailyPerWorkspace(Collection<Double> project1Scores,
            Collection<Double> project2Scores,
            String name,
            Function<Collection<Double>, Double> aggregator) {
        Collection<Double> allValues = Stream.concat(
                project1Scores.stream(),
                project2Scores.stream()).toList();

        return List.of(WorkspaceMetricResponse.Result.builder()
                .projectId(null)
                .name(name)
                .data(prepareMetricsAverageDailyData(allValues, aggregator))
                .build());
    }

    private List<DataPoint<Double>> prepareMetricsAverageDailyData(Collection<Double> scores,
            Function<Collection<Double>, Double> aggregator) {

        Instant todayTime = LocalDate
                .now(ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();

        Instant yesterdayTime = LocalDate
                .now(ZoneOffset.UTC)
                .minusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();

        var avgValue = scores.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        return List.of(DataPoint.<Double>builder()
                .time(yesterdayTime)
                .value(null)
                .build(),
                DataPoint.<Double>builder()
                        .time(todayTime)
                        .value(scores.isEmpty() ? null : aggregator.apply(scores))
                        .build());
    }

    private Double getAvg(Collection<Double> values) {
        return values.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }

    private Double getSum(Collection<Double> values) {
        return values.stream()
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    private List<Double> mapToCost(List<Span> spans) {
        return spans.stream()
                .map(Span::totalEstimatedCost)
                .map(BigDecimal::doubleValue)
                .collect(Collectors.toList());
    }

    @Nested
    @DisplayName("Workspace Configuration Tests")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class WorkspaceConfigurationTests {

        @Test
        @DisplayName("when upserting workspace configuration with valid data, then return success")
        void upsertWorkspaceConfiguration__whenValidData__thenReturnSuccess() {
            // Given
            Duration timeout = Duration.ofMinutes(30);
            WorkspaceConfiguration configuration = WorkspaceConfiguration.builder()
                    .timeoutToMarkThreadAsInactive(timeout)
                    .build();

            // When
            workspaceResourceClient.upsertWorkspaceConfiguration(configuration, API_KEY, WORKSPACE_NAME);

            // Then - Verify by getting the configuration
            WorkspaceConfiguration result = workspaceResourceClient.getWorkspaceConfiguration(API_KEY, WORKSPACE_NAME);
            assertThat(result.timeoutToMarkThreadAsInactive()).isEqualTo(timeout);
        }

        @Test
        @DisplayName("when upserting workspace configuration with null timeout, then return success")
        void upsertWorkspaceConfiguration__whenNullTimeout__thenReturnSuccess() {
            // Given
            WorkspaceConfiguration configuration = WorkspaceConfiguration.builder()
                    .timeoutToMarkThreadAsInactive(null)
                    .build();

            // When
            workspaceResourceClient.upsertWorkspaceConfiguration(configuration, API_KEY, WORKSPACE_NAME);

            // Then - Verify by getting the configuration
            WorkspaceConfiguration result = workspaceResourceClient.getWorkspaceConfiguration(API_KEY, WORKSPACE_NAME);
            assertThat(result.timeoutToMarkThreadAsInactive()).isNull();
        }

        @ParameterizedTest
        @MethodSource("validTimeoutDurations")
        @DisplayName("when upserting workspace configuration with various valid timeouts, then return success")
        void upsertWorkspaceConfiguration__whenVariousValidTimeouts__thenReturnSuccess(Duration timeout) {
            // Given
            WorkspaceConfiguration configuration = WorkspaceConfiguration.builder()
                    .timeoutToMarkThreadAsInactive(timeout)
                    .build();

            // When
            workspaceResourceClient.upsertWorkspaceConfiguration(configuration, API_KEY, WORKSPACE_NAME);

            // Then - Verify by getting the configuration
            WorkspaceConfiguration result = workspaceResourceClient.getWorkspaceConfiguration(API_KEY, WORKSPACE_NAME);
            assertThat(result.timeoutToMarkThreadAsInactive()).isEqualTo(timeout);
        }

        @ParameterizedTest
        @MethodSource("invalidTimeoutDurations")
        @DisplayName("when upserting workspace configuration with invalid timeouts, then return error")
        void upsertWorkspaceConfiguration__whenInvalidTimeouts__thenReturnError(Duration timeout) {
            // Given
            WorkspaceConfiguration configuration = WorkspaceConfiguration.builder()
                    .timeoutToMarkThreadAsInactive(timeout)
                    .build();

            // When
            try (Response response = workspaceResourceClient.callUpsertWorkspaceConfiguration(
                    configuration, API_KEY, WORKSPACE_NAME)) {

                // Then
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_CONTENT);
            }
        }

        @Test
        @DisplayName("when getting existing workspace configuration, then return configuration")
        void getWorkspaceConfiguration__whenConfigurationExists__thenReturnConfiguration() {
            // Given
            Duration timeout = Duration.ofHours(2);
            WorkspaceConfiguration configuration = WorkspaceConfiguration.builder()
                    .timeoutToMarkThreadAsInactive(timeout)
                    .build();

            // Create configuration first
            workspaceResourceClient.upsertWorkspaceConfiguration(configuration, API_KEY, WORKSPACE_NAME);

            // When
            WorkspaceConfiguration result = workspaceResourceClient.getWorkspaceConfiguration(API_KEY, WORKSPACE_NAME);

            // Then
            assertThat(result.timeoutToMarkThreadAsInactive()).isEqualTo(timeout);
        }

        @Test
        @DisplayName("when getting non-existing workspace configuration, then return not found")
        void getWorkspaceConfiguration__whenConfigurationDoesNotExist__thenReturnNotFound() {
            // Given
            String uniqueWorkspace = "unique-workspace-" + UUID.randomUUID();
            String uniqueApiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(uniqueApiKey, uniqueWorkspace, UUID.randomUUID().toString());

            // When
            try (Response response = workspaceResourceClient.callGetWorkspaceConfiguration(uniqueApiKey,
                    uniqueWorkspace)) {

                // Then
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("when deleting existing workspace configuration, then return success")
        void deleteWorkspaceConfiguration__whenConfigurationExists__thenReturnSuccess() {
            // Given
            Duration timeout = Duration.ofDays(1);
            WorkspaceConfiguration configuration = WorkspaceConfiguration.builder()
                    .timeoutToMarkThreadAsInactive(timeout)
                    .build();

            // Create configuration first
            workspaceResourceClient.upsertWorkspaceConfiguration(configuration, API_KEY, WORKSPACE_NAME);

            // When
            workspaceResourceClient.deleteWorkspaceConfiguration(API_KEY, WORKSPACE_NAME);

            // Then
            try (Response response = workspaceResourceClient.callGetWorkspaceConfiguration(API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("when deleting non-existing workspace configuration, then return no content")
        void deleteWorkspaceConfiguration__whenConfigurationDoesNotExist__thenReturnNoContent() {
            // Given
            String uniqueWorkspace = "unique-workspace-" + UUID.randomUUID();
            String uniqueApiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(uniqueApiKey, uniqueWorkspace, UUID.randomUUID().toString());

            // When
            try (Response response = workspaceResourceClient.callDeleteWorkspaceConfiguration(uniqueApiKey,
                    uniqueWorkspace)) {

                // Then
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            }
        }

        @Test
        @DisplayName("when upserting configuration with duration exceeding maximum, then return validation error")
        void upsertWorkspaceConfiguration__whenDurationExceedsMaximum__thenReturnValidationError() {
            // Given
            Duration exceedsMaxDuration = Duration.ofDays(8); // Max is 7 days
            WorkspaceConfiguration configuration = WorkspaceConfiguration.builder()
                    .timeoutToMarkThreadAsInactive(exceedsMaxDuration)
                    .build();

            // When
            try (Response response = workspaceResourceClient.callUpsertWorkspaceConfiguration(
                    configuration, API_KEY, WORKSPACE_NAME)) {

                // Then
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_CONTENT);
                assertThat(response.hasEntity()).isTrue();
                ErrorMessage errorMessage = response.readEntity(ErrorMessage.class);
                assertThat(errorMessage.errors())
                        .anyMatch(error -> error.contains("duration exceeds the maximum allowed"));
            }
        }

        @Test
        @DisplayName("when upserting configuration with sub-second precision, then return validation error")
        void upsertWorkspaceConfiguration__whenSubSecondPrecision__thenReturnValidationError() {
            // Given
            Duration subSecondDuration = Duration.ofMillis(500);
            WorkspaceConfiguration configuration = WorkspaceConfiguration.builder()
                    .timeoutToMarkThreadAsInactive(subSecondDuration)
                    .build();

            // When
            try (Response response = workspaceResourceClient.callUpsertWorkspaceConfiguration(
                    configuration, API_KEY, WORKSPACE_NAME)) {

                // Then
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_CONTENT);
            }
        }

        @Test
        @DisplayName("when upserting workspace configuration, then API uses ISO-8601 format for Duration")
        void upsertWorkspaceConfiguration__whenValidDuration__thenReturnsIso8601Format() {
            // Given
            Duration timeout = Duration.ofMinutes(30); // Should be "PT30M" in ISO-8601 format
            WorkspaceConfiguration configuration = WorkspaceConfiguration.builder()
                    .timeoutToMarkThreadAsInactive(timeout)
                    .build();

            // When - Make raw HTTP call to upsert
            try (Response upsertResponse = client
                    .target(String.format("%s/v1/private/workspaces/configurations", baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, WORKSPACE_NAME)
                    .put(Entity.json(configuration))) {

                // Then - Upsert should return 204
                assertThat(upsertResponse.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);

                // Get the configuration to check ISO-8601 format
                try (Response getResponse = client
                        .target(String.format("%s/v1/private/workspaces/configurations", baseURI))
                        .request()
                        .header(HttpHeaders.AUTHORIZATION, API_KEY)
                        .header(RequestContext.WORKSPACE_HEADER, WORKSPACE_NAME)
                        .get()) {

                    assertThat(getResponse.getStatus()).isEqualTo(HttpStatus.SC_OK);
                    String jsonResponse = getResponse.readEntity(String.class);
                    assertThat(jsonResponse).contains("\"PT30M\"");
                    assertThat(jsonResponse).doesNotContain("1800.000000000"); // Should not contain decimal seconds
                }
            }
        }

        static java.util.stream.Stream<Arguments> validTimeoutDurations() {
            return java.util.stream.Stream.of(
                    Arguments.of(Duration.ofSeconds(1)),
                    Arguments.of(Duration.ofMinutes(1)),
                    Arguments.of(Duration.ofMinutes(30)),
                    Arguments.of(Duration.ofHours(1)),
                    Arguments.of(Duration.ofHours(24)),
                    Arguments.of(Duration.ofDays(7)));
        }

        static java.util.stream.Stream<Arguments> invalidTimeoutDurations() {
            return java.util.stream.Stream.of(
                    Arguments.of(Duration.ofMillis(500)),
                    Arguments.of(Duration.ofMillis(999)),
                    Arguments.of(Duration.ofDays(8)),
                    Arguments.of(Duration.ofDays(30)));
        }

        @ParameterizedTest
        @MethodSource("validIso8601TimeoutStrings")
        @DisplayName("when upserting workspace configuration with valid ISO-8601 strings, then return success")
        void upsertWorkspaceConfiguration__whenValidIso8601Strings__thenReturnSuccess(String iso8601Duration,
                Duration expectedDuration) {
            // Given - Create a Map to properly structure JSON with ISO-8601 string
            Map<String, String> configMap = Map.of("timeout_to_mark_thread_as_inactive", iso8601Duration);

            // When
            try (Response upsertResponse = client
                    .target(String.format("%s/v1/private/workspaces/configurations", baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, WORKSPACE_NAME)
                    .put(Entity.json(configMap))) {

                // Then - Upsert should return 204
                assertThat(upsertResponse.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);

                // Verify by getting the configuration
                WorkspaceConfiguration result = workspaceResourceClient.getWorkspaceConfiguration(API_KEY,
                        WORKSPACE_NAME);
                assertThat(result.timeoutToMarkThreadAsInactive()).isEqualTo(expectedDuration);
            }
        }

        @ParameterizedTest
        @MethodSource("invalidIso8601TimeoutStrings")
        @DisplayName("when upserting workspace configuration with invalid ISO-8601 strings, then return validation error")
        void upsertWorkspaceConfiguration__whenInvalidIso8601Strings__thenReturnValidationError(
                String iso8601Duration, String expectedError) {
            // Given - Create a Map to properly structure JSON with invalid ISO-8601 string
            Map<String, String> configMap = Map.of("timeout_to_mark_thread_as_inactive", iso8601Duration);

            // When
            try (Response response = client.target(String.format("%s/v1/private/workspaces/configurations", baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, WORKSPACE_NAME)
                    .put(Entity.json(configMap))) {

                // Then
                assertThat(response.getStatus()).isIn(HttpStatus.SC_BAD_REQUEST, HttpStatus.SC_UNPROCESSABLE_CONTENT);

                if (response.getStatus() == HttpStatus.SC_UNPROCESSABLE_CONTENT) {
                    var error = response.readEntity(ErrorMessage.class);
                    assertThat(error.errors()).contains(expectedError);
                } else {
                    var errorMessage = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                    assertThat(errorMessage.getMessage()).isEqualTo(expectedError);
                }
            }
        }

        static java.util.stream.Stream<Arguments> validIso8601TimeoutStrings() {
            return java.util.stream.Stream.of(
                    Arguments.of("PT1S", Duration.ofSeconds(1)), // 1 second
                    Arguments.of("PT30S", Duration.ofSeconds(30)), // 30 seconds
                    Arguments.of("PT1M", Duration.ofMinutes(1)), // 1 minute
                    Arguments.of("PT30M", Duration.ofMinutes(30)), // 30 minutes
                    Arguments.of("PT1H", Duration.ofHours(1)), // 1 hour
                    Arguments.of("PT2H", Duration.ofHours(2)), // 2 hours
                    Arguments.of("P1D", Duration.ofDays(1)), // 1 day
                    Arguments.of("P7D", Duration.ofDays(7))); // 7 days (maximum)
        }

        static java.util.stream.Stream<Arguments> invalidIso8601TimeoutStrings() {
            return java.util.stream.Stream.of(
                    Arguments.of("PT0.5S",
                            "timeoutToMarkThreadAsInactive minimum precision supported is seconds, please use a duration with seconds precision or higher"),
                    Arguments.of("PT500MS",
                            "Unable to process JSON. Cannot parse Duration from string 'PT500MS': Text cannot be parsed to a Duration\n"
                                    +
                                    " at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 39] (through reference chain: com.comet.opik.api.WorkspaceConfiguration[\"timeout_to_mark_thread_as_inactive\"])"),
                    Arguments.of("P8D", "timeoutToMarkThreadAsInactive duration exceeds the maximum allowed of PT168H"),
                    Arguments.of("P30D",
                            "timeoutToMarkThreadAsInactive duration exceeds the maximum allowed of PT168H"),
                    Arguments.of("INVALID",
                            "Unable to process JSON. Cannot parse Duration from string 'INVALID': Text cannot be parsed to a Duration\n"
                                    +
                                    " at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 39] (through reference chain: com.comet.opik.api.WorkspaceConfiguration[\"timeout_to_mark_thread_as_inactive\"])"),
                    Arguments.of("30M",
                            "Unable to process JSON. Cannot parse Duration from string '30M': Text cannot be parsed to a Duration\n"
                                    +
                                    " at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 39] (through reference chain: com.comet.opik.api.WorkspaceConfiguration[\"timeout_to_mark_thread_as_inactive\"])"));
        }

        static Stream<Arguments> colorMapProvider() {
            return Stream.of(
                    Arguments.of(Map.of("accuracy", "#FF0000", "hallucination", "#00FF00")),
                    Arguments.of((Map<String, String>) null));
        }

        @ParameterizedTest
        @MethodSource("colorMapProvider")
        @DisplayName("when upserting workspace configuration with various color map values, then return success")
        void upsertWorkspaceConfiguration__whenColorMap__thenReturnSuccess(Map<String, String> colorMap) {
            var configuration = WorkspaceConfiguration.builder()
                    .colorMap(colorMap)
                    .build();

            workspaceResourceClient.upsertWorkspaceConfiguration(configuration, API_KEY, WORKSPACE_NAME);

            var result = workspaceResourceClient.getWorkspaceConfiguration(API_KEY, WORKSPACE_NAME);
            assertThat(result.colorMap()).isEqualTo(colorMap);
        }

        @Test
        @DisplayName("when upserting workspace configuration with invalid color hex, then return validation error")
        void upsertWorkspaceConfiguration__whenInvalidColorHex__thenReturnValidationError() {
            var colorMap = Map.of("accuracy", "not-a-color");
            var configuration = WorkspaceConfiguration.builder()
                    .colorMap(colorMap)
                    .build();

            try (var response = workspaceResourceClient.callUpsertWorkspaceConfiguration(
                    configuration, API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_CONTENT);
            }
        }

        @Test
        @DisplayName("when upserting workspace configuration with truncation toggle, then return success")
        void upsertWorkspaceConfiguration__whenTruncationToggle__thenReturnSuccess() {
            var configuration = WorkspaceConfiguration.builder()
                    .truncationOnTables(false)
                    .build();

            workspaceResourceClient.upsertWorkspaceConfiguration(configuration, API_KEY, WORKSPACE_NAME);

            var result = workspaceResourceClient.getWorkspaceConfiguration(API_KEY, WORKSPACE_NAME);
            assertThat(result.truncationOnTables()).isFalse();
        }

        @Test
        @DisplayName("when upserting workspace configuration with all properties, then return all properties")
        void upsertWorkspaceConfiguration__whenAllProperties__thenReturnAll() {
            var timeout = Duration.ofMinutes(15);
            var colorMap = Map.of("score1", "#AABBCC");
            var configuration = WorkspaceConfiguration.builder()
                    .timeoutToMarkThreadAsInactive(timeout)
                    .truncationOnTables(true)
                    .colorMap(colorMap)
                    .build();

            workspaceResourceClient.upsertWorkspaceConfiguration(configuration, API_KEY, WORKSPACE_NAME);

            var result = workspaceResourceClient.getWorkspaceConfiguration(API_KEY, WORKSPACE_NAME);
            assertThat(result.timeoutToMarkThreadAsInactive()).isEqualTo(timeout);
            assertThat(result.truncationOnTables()).isTrue();
            assertThat(result.colorMap()).isEqualTo(colorMap);
        }

        @Test
        @DisplayName("when upserting workspace configuration with null timeout, then return success with null")
        void upsertWorkspaceConfiguration__whenNullTimeout__thenReturnSuccessWithNull() {
            // Given - null timeout (use default)
            Map<String, Object> payload = Map.of(
            // Intentionally not including timeout_to_mark_thread_as_inactive to send null
            );

            // When
            try (Response upsertResponse = client
                    .target(String.format("%s/v1/private/workspaces/configurations", baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, WORKSPACE_NAME)
                    .put(Entity.json(payload))) {

                // Then - Upsert should return 204
                assertThat(upsertResponse.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);

                // Verify by getting the configuration
                WorkspaceConfiguration result = workspaceResourceClient.getWorkspaceConfiguration(API_KEY,
                        WORKSPACE_NAME);
                assertThat(result.timeoutToMarkThreadAsInactive()).isNull(); // Should be null (use default)
            }
        }

    }
}
