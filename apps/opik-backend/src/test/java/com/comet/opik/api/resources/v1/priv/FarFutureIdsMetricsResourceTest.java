package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Span;
import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.Trace;
import com.comet.opik.api.metrics.KpiCardRequest;
import com.comet.opik.api.metrics.KpiCardRequest.EntityType;
import com.comet.opik.api.metrics.KpiCardResponse;
import com.comet.opik.api.metrics.KpiCardResponse.KpiMetric;
import com.comet.opik.api.metrics.KpiCardResponse.KpiMetricType;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectMetricsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectMetricsDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Charts and KPI cards without a requested end count far-future ids (UUIDv7s minted with a bad client clock), as the
 * traces/spans/threads lists do. Ingestion rejects such ids by default, so this class disables that validation and seeds
 * them through the public endpoints.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Far-future ids in metrics and KPI cards")
@ExtendWith(DropwizardAppExtensionProvider.class)
class FarFutureIdsMetricsResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = RandomStringUtils.secure().nextAlphabetic(10);

    private static final Instant FAR_FUTURE = Instant.parse("2201-08-30T03:18:08Z");
    private static final double TOLERANCE = 0.1;

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, clickHouseContainer, mysql, zookeeperContainer).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysql);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysql.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        // Accepts far-future ids at ingestion, as production does.
                        .customConfigs(List.of(new CustomConfig("uuidValidation.enabled", "false")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private IdGenerator idGenerator;
    private ProjectResourceClient projectResourceClient;
    private ProjectMetricsResourceClient projectMetricsResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, IdGenerator idGenerator) {
        var baseURI = TestUtils.getBaseUrl(client);
        this.idGenerator = idGenerator;
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.projectMetricsResourceClient = new ProjectMetricsResourceClient(client, baseURI);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @ParameterizedTest
    @EnumSource(value = EntityType.class, names = {"TRACES", "SPANS"})
    @DisplayName("KPI cards count a far-future id in the current period when intervalEnd is null")
    void kpiCardsCountFarFutureIdsWithoutIntervalEnd(EntityType entityType) {
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var now = Instant.now();
        createEntities(entityType, projectName, List.of(now, FAR_FUTURE), List.of(100L, 300L), List.of(true, false),
                List.of(1.0, 3.0));

        var actual = projectResourceClient.getKpiCards(projectId, KpiCardRequest.builder()
                .entityType(entityType)
                .intervalStart(now.minus(1, ChronoUnit.MINUTES))
                .build(), API_KEY, WORKSPACE_NAME);

        var expected = KpiCardResponse.builder()
                .stats(List.of(
                        metric(KpiMetricType.COUNT, 2.0, 0.0),
                        metric(KpiMetricType.ERRORS, 50.0, 0.0),
                        metric(KpiMetricType.AVG_DURATION, 200.0, null),
                        metric(KpiMetricType.TOTAL_COST, 4.0, 0.0)))
                .build();
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringCollectionOrder()
                .withComparatorForType((a, b) -> Math.abs(a - b) <= TOLERANCE ? 0 : Double.compare(a, b), Double.class)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("charts count a far-future id in the latest bucket when intervalEnd is null")
    void chartCountsFarFutureIdInLatestBucketWithoutIntervalEnd() {
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var now = Instant.now();
        var today = now.truncatedTo(ChronoUnit.DAYS);
        createEntities(EntityType.TRACES, projectName, List.of(now, FAR_FUTURE), List.of(100L, 300L),
                List.of(false, false), List.of(1.0, 3.0));
        var request = ProjectMetricRequest.builder()
                .metricType(MetricType.TRACE_COUNT)
                .interval(TimeInterval.DAILY)
                .intervalStart(today.minus(2, ChronoUnit.DAYS))
                .build();

        var actual = projectMetricsResourceClient.getProjectMetrics(projectId, request, Integer.class, API_KEY,
                WORKSPACE_NAME);

        var expected = ProjectMetricResponse.<Integer>builder()
                .projectId(projectId)
                .metricType(MetricType.TRACE_COUNT)
                .interval(TimeInterval.DAILY)
                .results(List.of(ProjectMetricResponse.Results.<Integer>builder()
                        .name(ProjectMetricsDAO.NAME_TRACES)
                        .data(List.of(
                                DataPoint.<Integer>builder().time(today.minus(2, ChronoUnit.DAYS)).value(0).build(),
                                DataPoint.<Integer>builder().time(today.minus(1, ChronoUnit.DAYS)).value(0).build(),
                                DataPoint.<Integer>builder().time(today).value(2).build()))
                        .build()))
                .build();
        assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
    }

    @Test
    @DisplayName("thread KPI cards count a far-future thread and its far-future span's cost in the current period when intervalEnd is null")
    void threadKpiCardsCountFarFutureIdsWithoutIntervalEnd() {
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var now = Instant.now();
        createThreads(projectId, projectName, List.of(now, FAR_FUTURE), List.of(100L, 300L), List.of(1.0, 3.0));

        var actual = projectResourceClient.getKpiCards(projectId, KpiCardRequest.builder()
                .entityType(EntityType.THREADS)
                .intervalStart(now.minus(1, ChronoUnit.MINUTES))
                .build(), API_KEY, WORKSPACE_NAME);

        var expected = KpiCardResponse.builder()
                .stats(List.of(
                        metric(KpiMetricType.COUNT, 2.0, 0.0),
                        metric(KpiMetricType.AVG_DURATION, 200.0, null),
                        metric(KpiMetricType.TOTAL_COST, 4.0, 0.0)))
                .build();
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringCollectionOrder()
                .withComparatorForType((a, b) -> Math.abs(a - b) <= TOLERANCE ? 0 : Double.compare(a, b), Double.class)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("the thread cost chart counts a far-future thread's cost in the latest bucket when intervalEnd is null")
    void threadCostChartCountsFarFutureIdInLatestBucketWithoutIntervalEnd() {
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var now = Instant.now();
        var today = now.truncatedTo(ChronoUnit.DAYS);
        createThreads(projectId, projectName, List.of(now, FAR_FUTURE), List.of(100L, 300L), List.of(1.0, 3.0));
        var request = ProjectMetricRequest.builder()
                .metricType(MetricType.THREAD_COST)
                .interval(TimeInterval.DAILY)
                .intervalStart(today.minus(2, ChronoUnit.DAYS))
                .build();

        var actual = projectMetricsResourceClient.getProjectMetrics(projectId, request, BigDecimal.class, API_KEY,
                WORKSPACE_NAME);

        var expected = ProjectMetricResponse.<BigDecimal>builder()
                .projectId(projectId)
                .metricType(MetricType.THREAD_COST)
                .interval(TimeInterval.DAILY)
                .results(List.of(ProjectMetricResponse.Results.<BigDecimal>builder()
                        .name(ProjectMetricsDAO.NAME_THREAD_COST)
                        .data(List.of(
                                DataPoint.<BigDecimal>builder().time(today.minus(2, ChronoUnit.DAYS))
                                        .value(BigDecimal.ZERO).build(),
                                DataPoint.<BigDecimal>builder().time(today.minus(1, ChronoUnit.DAYS))
                                        .value(BigDecimal.ZERO).build(),
                                DataPoint.<BigDecimal>builder().time(today).value(BigDecimal.valueOf(4)).build()))
                        .build()))
                .build();
        assertThat(actual)
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(expected);
    }

    /** One trace per entry, each with one span; the trace and its span take their id from the entry's instant. */
    private void createEntities(EntityType entityType, String projectName, List<Instant> idTimes,
            List<Long> durationsMs, List<Boolean> hasErrors, List<Double> costs) {
        createEntities(entityType, projectName, idTimes, durationsMs, hasErrors, costs, List.of());
    }

    private void createThreads(UUID projectId, String projectName, List<Instant> idTimes, List<Long> durationsMs,
            List<Double> costs) {
        var threadIds = idTimes.stream().map(_ -> RandomStringUtils.secure().nextAlphabetic(10)).toList();
        createEntities(EntityType.THREADS, projectName, idTimes, durationsMs,
                idTimes.stream().map(_ -> false).toList(), costs, threadIds);
        traceResourceClient.awaitThreadRows(threadIds, projectId, null, API_KEY, WORKSPACE_NAME);
    }

    private void createEntities(EntityType entityType, String projectName, List<Instant> idTimes,
            List<Long> durationsMs, List<Boolean> hasErrors, List<Double> costs, List<String> threadIds) {
        var traces = new ArrayList<Trace>();
        var spans = new ArrayList<Span>();
        for (int i = 0; i < idTimes.size(); i++) {
            var start = Instant.now();
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.getTimeOrderedEpoch(idTimes.get(i).toEpochMilli()))
                    .projectName(projectName)
                    .startTime(start)
                    .endTime(start.plus(durationsMs.get(i), ChronoUnit.MILLIS))
                    .errorInfo(entityType == EntityType.TRACES && hasErrors.get(i) ? errorInfo() : null)
                    .threadId(threadIds.isEmpty() ? null : threadIds.get(i))
                    .build();
            traces.add(trace);
            spans.add(factory.manufacturePojo(Span.class).toBuilder()
                    .id(idGenerator.getTimeOrderedEpoch(idTimes.get(i).plusMillis(1).toEpochMilli()))
                    .traceId(trace.id())
                    .projectName(projectName)
                    .startTime(start)
                    .endTime(start.plus(durationsMs.get(i), ChronoUnit.MILLIS))
                    .totalEstimatedCost(BigDecimal.valueOf(costs.get(i)))
                    .errorInfo(entityType == EntityType.SPANS && hasErrors.get(i) ? errorInfo() : null)
                    .feedbackScores(null)
                    .build());
        }
        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
    }

    private static KpiMetric metric(KpiMetricType type, Double current, Double previous) {
        return KpiMetric.builder().type(type).currentValue(current).previousValue(previous).build();
    }

    private static ErrorInfo errorInfo() {
        return ErrorInfo.builder().exceptionType("TestError").message("test error").traceback("traceback").build();
    }
}
