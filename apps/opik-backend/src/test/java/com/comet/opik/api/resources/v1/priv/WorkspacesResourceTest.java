package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.FeedbackScoreBatchItem;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
    private WorkspaceResourceClient workspaceResourceClient;
    private IdGenerator idGenerator;

    @BeforeAll
    void setUpAll(ClientSupport client, IdGenerator idGenerator) throws SQLException {

        this.baseURI = TestUtils.getBaseUrl(client);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
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
    class FeedbackScoresTest {

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void metricsSummary_happyPath(boolean withProjectIds) throws InterruptedException {
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

            var traces = traceResourceClient.getByProjectName(projectName, apiKey, workspaceName);

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

            var expectedMetricsSummary = withProjectIds
                    ? prepareMetricsDailyPerProjects(project1Scores, project2Scores, name, projectId, projectId2)
                    : prepareMetricsDailyPerWorkspace(project1Scores, project2Scores, name);

            assertThat(actualMetrics.results())
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .withComparatorForType(StatsUtils::closeToEpsilonComparator, Double.class)
                    .isEqualTo(expectedMetricsSummary);
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
                var expectedMetricsSummary = prepareMetricsDailyPerWorkspace(Map.of(), Map.of(), name);

                assertThat(actualMetrics.results())
                        .usingRecursiveComparison()
                        .ignoringCollectionOrder()
                        .isEqualTo(expectedMetricsSummary);
            }
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
                                .build();

                        traceResourceClient.createTrace(trace, apiKey, workspaceName);

                        // create several feedback scores for that trace
                        List<FeedbackScoreBatchItem> scores = scoreNames.stream()
                                .map(name -> factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                        .name(name)
                                        .projectName(projectName)
                                        .id(trace.id())
                                        .build())
                                .toList();

                        traceResourceClient.feedbackScores(scores, apiKey, workspaceName);

                        return scores;
                    })
                    .flatMap(List::stream)
                    .collect(Collectors.groupingBy(FeedbackScoreBatchItem::name))
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> calcAverage(e.getValue().stream().map(FeedbackScoreBatchItem::value)
                                    .toList())));
        }

        private static Double calcAverage(List<BigDecimal> scores) {
            BigDecimal sum = scores.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return sum.divide(new BigDecimal(scores.size()), RoundingMode.UP).doubleValue();
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

        private List<WorkspaceMetricResponse.Result> prepareMetricsDailyPerProjects(Map<String, Double> project1Scores,
                Map<String, Double> project2Scores,
                String name, UUID projectId1, UUID projectId2) {
            return List.of(WorkspaceMetricResponse.Result.builder()
                    .projectId(projectId1)
                    .name(name)
                    .data(prepareMetricsDailyData(project1Scores.values()))
                    .build(),
                    WorkspaceMetricResponse.Result.builder()
                            .projectId(projectId2)
                            .name(name)
                            .data(prepareMetricsDailyData(project2Scores.values()))
                            .build());
        }

        private List<WorkspaceMetricResponse.Result> prepareMetricsDailyPerWorkspace(Map<String, Double> project1Scores,
                Map<String, Double> project2Scores,
                String name) {
            Collection<Double> allValues = Stream.concat(
                    project1Scores.values().stream(),
                    project2Scores.values().stream()).toList();

            return List.of(WorkspaceMetricResponse.Result.builder()
                    .projectId(null)
                    .name(name)
                    .data(prepareMetricsDailyData(allValues))
                    .build());
        }

        private List<DataPoint<Double>> prepareMetricsDailyData(Collection<Double> scores) {

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
                            .value(scores.isEmpty() ? null : avgValue)
                            .build());
        }
    }
}