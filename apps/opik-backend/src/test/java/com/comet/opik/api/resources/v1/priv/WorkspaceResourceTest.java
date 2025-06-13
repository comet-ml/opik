package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Trace;
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
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.resources.WorkspaceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.jdbi.v3.core.Jdbi;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Workspace Metrics Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class WorkspaceResourceTest {
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

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private WorkspaceResourceClient workspaceResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws SQLException {
        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.workspaceResourceClient = new WorkspaceResourceClient(client, baseURI, factory);

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
        void happyPath(boolean withProjectIds) {
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String projectName = RandomStringUtils.randomAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
            List<String> names = PodamFactoryUtils.manufacturePojoList(factory, String.class);

            var previousScores = createFeedbackScores(projectName, names.subList(0, names.size() - 1), apiKey,
                    workspaceName);
            var currentScores = createFeedbackScores(projectName, names.subList(1, names.size()), apiKey,
                    workspaceName);

            var traces = traceResourceClient.getByProjectName(projectName, apiKey, workspaceName);
            var startTime = traces.stream()
                    .flatMap(trace -> trace.feedbackScores().stream())
                    .filter(score -> score.name().equals(names.getLast()))
                    .map(FeedbackScore::createdAt)
                    .min(Instant::compareTo).get();
            Instant endTime = startTime.plus(Duration.ofMinutes(10));

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
        void emptyData(boolean withProjectIds) {
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

        private Map<String, Double> createFeedbackScores(String projectName, List<String> scoreNames, String apiKey,
                String workspaceName) {
            return IntStream.range(0, 5)
                    .mapToObj(i -> {
                        // create a trace
                        Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
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
                            .current(currentScores.getOrDefault(name, 0D))
                            .previous(previousScores.getOrDefault(name, 0D))
                            .build())
                    .toList();
        }
    }
}