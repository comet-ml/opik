package com.comet.opik.infrastructure;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.ExperimentsTestUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesService;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.SpanWeeksReadFixtures.FarFutureTrace;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions.EXPERIMENT_IGNORED_FIELDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trace-keyed spans reads bounded by the span_weeks index ({@code spanWeeksReadEnabled}) return every span of
 * their traces, a far-future one included, on the weekly-partitioned spans table: each read is compared as a whole
 * against what its traces' spans add up to. Live registration is on, and each fixture waits for its weeks to be
 * indexed; the async gap after a write is accepted behaviour, not under test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpanWeeksReadBoundTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private static final String[] OPTIMIZATION_IGNORED_FIELDS = {"datasetId", "createdAt", "lastUpdatedAt",
            "createdBy", "lastUpdatedBy", "studioConfig", "datasetName", "baselineObjectiveScore",
            "bestObjectiveScore", "baselineDuration", "bestDuration", "experimentScores", "projectName"};

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeperContainer);
    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        // The successor has no public API before the cutover, so it is installed the way the cutover does it.
        TransactionTemplateAsync.create(databaseAnalyticsFactory.build())
                .nonTransaction(connection -> Mono.from(connection
                        .createStatement("EXCHANGE TABLES spans AND spans_local_v2 ON CLUSTER '{cluster}'")
                        .execute()))
                .block();
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(List.of(
                                // Lets the far-future ids through ingestion, as in production.
                                new CustomConfig("uuidValidation.enabled", "false"),
                                // The successor's columns are non-nullable, as the cutover flips this with it.
                                new CustomConfig("databaseAnalyticsDataModel.spanColumnsNonNullable", "true"),
                                new CustomConfig("databaseAnalyticsDataModel.spanWeeksWriteEnabled", "true"),
                                new CustomConfig("databaseAnalyticsDataModel.spanWeeksReadEnabled", "true"),
                                // Finished experiments aggregate after this delay; its bucket lives twice as long, so
                                // the job's 5s tick always finds it.
                                new CustomConfig("experimentDenormalization.debounceDelay", "5s")))
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private OptimizationResourceClient optimizationResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ExperimentAggregatesService experimentAggregatesService;
    private SpanWeeksReadFixtures fixtures;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, SpanService spanService,
            ExperimentAggregatesService experimentAggregatesService) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        this.optimizationResourceClient = new OptimizationResourceClient(clientSupport, baseUrl, factory);
        this.datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        this.experimentAggregatesService = experimentAggregatesService;
        this.fixtures = new SpanWeeksReadFixtures(API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER, factory,
                new ProjectResourceClient(clientSupport, baseUrl, factory), traceResourceClient,
                new SpanResourceClient(clientSupport, baseUrl), datasetResourceClient, experimentResourceClient,
                spanService);
    }

    @Test
    void traceCountFilteredOnSpanCostCountsAFarFutureSpan() {
        var trace = fixtures.createFarFutureTrace();
        // The trace reaches its full cost only with the far-future span, so the filter keeps it only if that is read.
        var filters = List.of(TraceFilter.builder()
                .field(TraceField.TOTAL_ESTIMATED_COST)
                .operator(Operator.GREATER_THAN_EQUAL)
                .value(trace.totalCost().toPlainString())
                .build());

        var actual = traceResourceClient.getTraces(trace.projectName(), null, API_KEY, WORKSPACE_NAME, filters, null,
                10, Map.of());

        TraceAssertions.assertPage(actual, 1, 1, 1);
        TraceAssertions.assertTraces(actual.content(), List.of(expectedTrace(trace)), USER);
    }

    @Test
    void experimentByIdCountsAFarFutureSpanOfItsTrace() {
        var trace = fixtures.createFarFutureTrace();
        var created = fixtures.createExperiment(trace.trace().id(), null);

        var actual = experimentResourceClient.getExperiment(created.experiment().id(), API_KEY, WORKSPACE_NAME);

        assertExperiment(actual, expectedExperiment(created.experiment(), trace));
    }

    @Test
    void experimentAggregatesCountAFarFutureSpanOfItsTrace() {
        var trace = fixtures.createFarFutureTrace();
        var created = fixtures.createExperiment(trace.trace().id(), null);

        experimentResourceClient.finishExperiments(Set.of(created.experiment().id()), API_KEY,
                WORKSPACE_NAME);

        // The aggregate row itself: the API falls back to raw span sums while it is missing. The row does not
        // carry the dataset name or version summary, and holds the experiment scores in its own order.
        var expected = expectedExperiment(created.experiment(), trace).toBuilder()
                .datasetName(null)
                .datasetVersionSummary(null)
                .build();
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(experimentAggregatesService
                        .getExperimentFromAggregates(created.experiment().id())
                        .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, USER)
                                .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                        .blockOptional())
                        .hasValueSatisfying(actual -> assertThat(actual)
                                .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                                        .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                        .build())
                                .ignoringFields(EXPERIMENT_IGNORED_FIELDS)
                                .ignoringCollectionOrderInFields("experimentScores")
                                .isEqualTo(expected)));
    }

    @Test
    void datasetExperimentItemsStatsCountAFarFutureSpanOfTheTrace() {
        var trace = fixtures.createFarFutureTrace();
        var created = fixtures.createExperiment(trace.trace().id(), null);

        var actual = datasetResourceClient.getDatasetExperimentItemsStats(created.datasetId(),
                List.of(created.experiment().id()), API_KEY, WORKSPACE_NAME, null);

        TraceAssertions.assertStats(actual.stats(), expectedExperimentItemsStats(trace));
    }

    @Test
    void optimizationByIdCountsAFarFutureSpanOfItsTrace() {
        var trace = fixtures.createFarFutureTrace();
        var optimization = optimizationResourceClient.createPartialOptimization().build();
        var optimizationId = optimizationResourceClient.create(optimization, API_KEY, WORKSPACE_NAME);
        fixtures.createExperiment(trace.trace().id(), optimizationId);

        var actual = optimizationResourceClient.get(optimizationId, API_KEY, WORKSPACE_NAME, HttpStatus.SC_OK);

        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(OPTIMIZATION_IGNORED_FIELDS)
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .isEqualTo(optimization.toBuilder()
                        .id(optimizationId)
                        .numTrials(1L)
                        .baselineCost(trace.totalCost())
                        .totalOptimizationCost(trace.totalCost())
                        .build());
    }

    @Test
    void threadByIdCountsAFarFutureSpanOfItsTrace() {
        var trace = fixtures.createFarFutureTrace();

        // Threads are materialised from their traces asynchronously.
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> TraceAssertions.assertThreads(
                List.of(expectedThread(trace)),
                List.of(traceResourceClient.getTraceThread(trace.trace().threadId(), trace.projectId(), API_KEY,
                        WORKSPACE_NAME))));
    }

    /** The thread list's three span reads: the page pushdown, the trace-set prefilter, and the plain window. */
    @ParameterizedTest
    @ValueSource(strings = {"page pushdown", "trace set", "window"})
    void threadListCountsAFarFutureSpanOfItsTrace(String variant) {
        var trace = fixtures.createFarFutureTrace();
        // A thread id filter activates the trace-set prefilter; a cost sort disqualifies the page pushdown.
        var filters = variant.equals("trace set") ? threadIdFilter(trace.trace().threadId()) : null;
        var sorting = variant.equals("window")
                ? List.of(SortingField.builder().field(SortableFields.TOTAL_ESTIMATED_COST)
                        .direction(Direction.DESC).build())
                : null;

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> TraceAssertions.assertThreads(
                List.of(expectedThread(trace)),
                traceResourceClient.getTraceThreads(trace.projectId(), null, API_KEY, WORKSPACE_NAME, filters,
                        sorting, Map.of()).content()));
    }

    @ParameterizedTest(name = "trace set: {0}")
    @ValueSource(booleans = {false, true})
    void threadStatsCountAFarFutureSpanOfItsTrace(boolean traceSet) {
        var trace = fixtures.createFarFutureTrace();
        var filters = traceSet ? threadIdFilter(trace.trace().threadId()) : null;
        // The helper prices spans from their model and usage; these carry an explicit cost, so the two cost stats
        // are the trace's own total (one thread: the average is the sum).
        var expected = StatsUtils.getProjectThreadStatItems(List.of(trace.trace()), trace.spans(), null)
                .stream().<ProjectStats.ProjectStatItem<?>>map(stat -> switch (stat.getName()) {
                    case "total_estimated_cost", "total_estimated_cost_sum" ->
                        ProjectStats.AvgValueStat.builder().name(stat.getName()).value(trace.totalCost().doubleValue())
                                .type(ProjectStats.StatsType.AVG).build();
                    default -> stat;
                })
                .toList();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> TraceAssertions.assertStats(
                traceResourceClient.getTraceThreadStats(null, trace.projectId(), API_KEY, WORKSPACE_NAME, filters,
                        Map.of()).stats(),
                expected));
    }

    private static Trace expectedTrace(FarFutureTrace trace) {
        return trace.trace().toBuilder()
                .projectId(trace.projectId())
                .usage(trace.totalUsage())
                .totalEstimatedCost(trace.totalCost())
                .spanCount(trace.spans().size())
                .build();
    }

    /** One trace, so the per-trace averages are the trace's own totals. */
    private static Experiment expectedExperiment(Experiment experiment, FarFutureTrace trace) {
        var quantiles = ExperimentsTestUtils.getQuantities(Stream.of(trace.trace()));
        return experiment.toBuilder()
                .projectId(trace.projectId())
                .duration(PercentageValues.builder().p50(quantiles.get(0)).p90(quantiles.get(1)).p99(quantiles.get(2))
                        .build())
                .totalEstimatedCost(trace.totalCost())
                .totalEstimatedCostAvg(trace.totalCost())
                .usage(StatsUtils.calculateUsageAverage(List.of(trace.totalUsage())))
                .build();
    }

    private static void assertExperiment(Experiment actual, Experiment expected) {
        assertThat(actual)
                .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                        .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                        .build())
                .ignoringFields(EXPERIMENT_IGNORED_FIELDS)
                .isEqualTo(expected);
    }

    /** One experiment item over one trace: every average and percentile is that trace's own value. */
    private static List<ProjectStats.ProjectStatItem<?>> expectedExperimentItemsStats(FarFutureTrace trace) {
        var cost = trace.totalCost().doubleValue();
        var duration = DurationUtils.getDurationInMillisWithSubMilliPrecision(trace.trace().startTime(),
                trace.trace().endTime());
        var durationPercentiles = StatsUtils.calculateQuantiles(List.of(duration), List.of(0.50, 0.90, 0.99));
        var costPercentiles = StatsUtils.calculateQuantiles(List.of(cost), List.of(0.50, 0.90, 0.99));
        var stats = new ArrayList<ProjectStats.ProjectStatItem<?>>(List.of(
                ProjectStats.CountValueStat.builder().name(StatsMapper.EXPERIMENT_ITEMS_COUNT).value(1L)
                        .type(ProjectStats.StatsType.COUNT).build(),
                ProjectStats.CountValueStat.builder().name(StatsMapper.TRACE_COUNT).value(1L)
                        .type(ProjectStats.StatsType.COUNT).build(),
                ProjectStats.AvgValueStat.builder().name(StatsMapper.TOTAL_ESTIMATED_COST).value(cost)
                        .type(ProjectStats.StatsType.AVG).build(),
                ProjectStats.PercentageValueStat.builder().name(StatsMapper.TOTAL_ESTIMATED_COST)
                        .value(PercentageValues.builder().p50(costPercentiles.get(0)).p90(costPercentiles.get(1))
                                .p99(costPercentiles.get(2)).build())
                        .type(ProjectStats.StatsType.PERCENTAGE).build(),
                ProjectStats.PercentageValueStat.builder().name(StatsMapper.DURATION)
                        .value(PercentageValues.builder().p50(durationPercentiles.get(0))
                                .p90(durationPercentiles.get(1)).p99(durationPercentiles.get(2)).build())
                        .type(ProjectStats.StatsType.PERCENTAGE).build()));
        trace.totalUsage().forEach((key, value) -> stats.add(
                ProjectStats.AvgValueStat.builder().name("%s.%s".formatted(StatsMapper.USAGE, key))
                        .value(value.doubleValue()).type(ProjectStats.StatsType.AVG).build()));
        // Always reported, 0 when no span carries a total_tokens usage.
        var totalTokens = BigDecimal.valueOf(trace.totalUsage().getOrDefault("total_tokens", 0L));
        stats.add(ProjectStats.PercentageValueStat.builder().name("%s.total_tokens".formatted(StatsMapper.USAGE))
                .value(PercentageValues.builder().p50(totalTokens).p90(totalTokens).p99(totalTokens).build())
                .type(ProjectStats.StatsType.PERCENTAGE).build());
        return stats;
    }

    private static TraceThread expectedThread(FarFutureTrace trace) {
        var source = trace.trace();
        return TraceThread.builder()
                .id(source.threadId())
                .projectId(trace.projectId())
                .firstMessage(source.input())
                .lastMessage(source.output())
                .startTime(source.startTime())
                .endTime(source.endTime())
                .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(source.startTime(),
                        source.endTime()))
                .numberOfMessages(2L)
                .totalEstimatedCost(trace.totalCost())
                .usage(trace.totalUsage())
                .status(TraceThreadStatus.ACTIVE)
                .environment(source.environment())
                .build();
    }

    private static List<TraceThreadFilter> threadIdFilter(String threadId) {
        return List.of(TraceThreadFilter.builder()
                .field(TraceThreadField.ID)
                .operator(Operator.EQUAL)
                .value(threadId)
                .build());
    }
}
