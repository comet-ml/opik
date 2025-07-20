package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.metrics.WorkspaceMetricRequest;
import com.comet.opik.api.metrics.WorkspaceMetricResponse;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryRequest;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
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
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.resources.WorkspaceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
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
import static org.assertj.core.api.Assertions.assertThat;

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
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
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
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private WorkspaceResourceClient workspaceResourceClient;
    private IdGenerator idGenerator;

    @BeforeAll
    void setUpAll(ClientSupport client, IdGenerator idGenerator) throws SQLException {

        this.baseURI = TestUtils.getBaseUrl(client);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.workspaceResourceClient = new WorkspaceResourceClient(client, baseURI, factory);
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
    @DisplayName("Feedback scores metrics")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FeedbackScoresMetricsTest {

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
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
}