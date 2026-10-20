package com.comet.opik.infrastructure;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
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
import com.comet.opik.api.resources.utils.spans.SpanAssertions;
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
import com.comet.opik.utils.AsyncUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions.EXPERIMENT_IGNORED_FIELDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The trace-keyed spans reads bounded by the span_weeks index ({@code spanWeeksReadEnabled}) return every span of
 * their traces, a far-future one included, on the weekly-partitioned spans table: each read is compared as a whole
 * against what its traces' spans add up to. Weeks are registered by {@code span_weeks_mv}, and each fixture waits for
 * them to be indexed unless it says otherwise.
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
        installPartitionedSpans(databaseAnalyticsFactory);
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
                                new CustomConfig("databaseAnalyticsDataModel.spanWeeksReadEnabled", "true"),
                                // Finished experiments aggregate after this delay; its bucket lives twice as long, so
                                // the job's 5s tick always finds it.
                                new CustomConfig("experimentDenormalization.debounceDelay", "5s")))
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private OptimizationResourceClient optimizationResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ExperimentAggregatesService experimentAggregatesService;
    private SpanService spanService;
    private SpanWeeksReadFixtures fixtures;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, SpanService spanService,
            ExperimentAggregatesService experimentAggregatesService) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        this.experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        this.optimizationResourceClient = new OptimizationResourceClient(clientSupport, baseUrl, factory);
        this.datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        this.experimentAggregatesService = experimentAggregatesService;
        this.spanService = spanService;
        this.fixtures = new SpanWeeksReadFixtures(API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER, factory,
                new ProjectResourceClient(clientSupport, baseUrl, factory), traceResourceClient,
                spanResourceClient, datasetResourceClient, experimentResourceClient,
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

    /** A thread id filter activates the trace-set prefilter; a cost sort disqualifies the page pushdown. */
    private Stream<Arguments> threadListCountsAFarFutureSpanOfItsTrace() {
        var pagePushdownThreadId = fixtures.randomThreadId();
        var traceSetThreadId = fixtures.randomThreadId();
        var windowThreadId = fixtures.randomThreadId();
        return Stream.of(
                arguments(Named.of("page pushdown", pagePushdownThreadId), null, null),
                arguments(Named.of("trace set", traceSetThreadId), threadIdFilter(traceSetThreadId), null),
                arguments(Named.of("window", windowThreadId), null, List.of(SortingField.builder()
                        .field(SortableFields.TOTAL_ESTIMATED_COST)
                        .direction(Direction.DESC)
                        .build())));
    }

    /** The thread list's three span reads: the page pushdown, the trace-set prefilter, and the plain window. */
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void threadListCountsAFarFutureSpanOfItsTrace(String threadId, List<TraceThreadFilter> filters,
            List<SortingField> sorting) {
        var trace = fixtures.createFarFutureTrace(threadId);

        var expectedThreads = List.of(expectedThread(trace));
        // Threads are materialised from their traces asynchronously.
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var actualThreads = traceResourceClient.getTraceThreads(trace.projectId(), null, API_KEY, WORKSPACE_NAME,
                    filters, sorting, Map.of()).content();
            TraceAssertions.assertThreads(expectedThreads, actualThreads);
        });
    }

    private Stream<Arguments> threadStatsCountAFarFutureSpanOfItsTrace() {
        var projectThreadId = fixtures.randomThreadId();
        var traceSetThreadId = fixtures.randomThreadId();
        return Stream.of(
                arguments(Named.of("project", projectThreadId), null),
                arguments(Named.of("trace set", traceSetThreadId), threadIdFilter(traceSetThreadId)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void threadStatsCountAFarFutureSpanOfItsTrace(String threadId, List<TraceThreadFilter> filters) {
        var trace = fixtures.createFarFutureTrace(threadId);

        // One thread: its average cost is its total.
        var expectedStats = withExplicitCost(
                StatsUtils.getProjectThreadStatItems(List.of(trace.trace()), trace.spans(), null),
                trace.totalCost(), trace.totalCost());
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var actualStats = traceResourceClient.getTraceThreadStats(null, trace.projectId(), API_KEY,
                    WORKSPACE_NAME, filters, Map.of()).stats();
            TraceAssertions.assertStats(actualStats, expectedStats);
        });
    }

    /**
     * No span has a score, so an is-empty feedback score filter keeps both, and turns on the span id prefilter. A
     * custom sort reads through {@code spans_deduped}; by id descending it keeps the default order.
     */
    private Stream<Arguments> traceSpansPageCountsAFarFutureSpan() {
        return Stream.of(
                arguments(Named.of("no filter", null), null),
                arguments(Named.of("feedback score filter", List.of(SpanFilter.builder()
                        .field(SpanField.FEEDBACK_SCORES)
                        .operator(Operator.IS_EMPTY)
                        .key("score-" + RandomStringUtils.secure().nextAlphanumeric(32))
                        .value("")
                        .build())), null),
                arguments(Named.of("custom sort", null), List.of(SortingField.builder()
                        .field(SortableFields.ID)
                        .direction(Direction.DESC)
                        .build())));
    }

    /**
     * The spans of a trace and their count, as the trace detail reads them: the default sort pages in the level that
     * reads spans, a custom sort pages over the deduped spans, and a feedback score filter adds the span id prefilter
     * of both statements.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void traceSpansPageCountsAFarFutureSpan(List<SpanFilter> filters, List<SortingField> sorting) {
        var trace = fixtures.createFarFutureTrace();

        var actualPage = spanResourceClient.findSpans(WORKSPACE_NAME, API_KEY, trace.projectName(), null, 1, 10,
                trace.trace().id(), null, filters, sorting, null);

        var expectedSpans = fixtures.expectedSpans(trace.spans());
        SpanAssertions.assertPage(actualPage, 1, expectedSpans.size(), expectedSpans.size());
        SpanAssertions.assertSpan(actualPage.content(), expectedSpans, USER);
    }

    /** A search within the trace relies on the span_weeks bound alone; its week pre-pass is skipped. */
    @Test
    void traceSpansSearchCountsAFarFutureSpan() {
        var trace = fixtures.createFarFutureTrace();
        // Every span of the trace matches its trace id.
        var search = trace.trace().id().toString();

        var actualPage = spanResourceClient.findSpans(WORKSPACE_NAME, API_KEY, trace.projectName(), null, 1, 10,
                trace.trace().id(), null, null, null, null, null, null, search);

        var expectedSpans = fixtures.expectedSpans(trace.spans());
        SpanAssertions.assertPage(actualPage, 1, expectedSpans.size(), expectedSpans.size());
        SpanAssertions.assertSpan(actualPage.content(), expectedSpans, USER);
    }

    /**
     * {@code span_weeks_mv} indexes a span's week by the time its insert returns, so a trace's spans are read in full
     * right after their write, with no wait for the index.
     */
    @Test
    void traceSpansPageReturnsSpansRightAfterTheirWrite() {
        var trace = fixtures.createFarFutureTraceWithoutWaiting();

        var actualPage = spanResourceClient.getByTraceIdAndProject(trace.trace().id(), trace.projectName(),
                WORKSPACE_NAME, API_KEY);

        var expectedSpans = fixtures.expectedSpans(trace.spans());
        SpanAssertions.assertPage(actualPage, 1, expectedSpans.size(), expectedSpans.size());
        SpanAssertions.assertSpan(actualPage.content(), expectedSpans, USER);
    }

    @Test
    void traceSpansStreamIncludesAFarFutureSpan() {
        var trace = fixtures.createFarFutureTrace();

        var actualSpans = fixtures.streamTraceSpans(trace);

        var expectedSpans = fixtures.expectedSpans(trace.spans());
        SpanAssertions.assertSpan(actualSpans, expectedSpans, USER);
    }

    @Test
    void spansByTraceIdsIncludeAFarFutureSpan() {
        var trace = fixtures.createFarFutureTrace();

        var actualSpanIds = fixtures.getSpanIdsOfDatasetItemFromTrace(trace.trace().id());

        var expectedSpanIds = trace.spans().stream().map(Span::id).toList();
        assertThat(actualSpanIds).containsExactlyInAnyOrderElementsOf(expectedSpanIds);
    }

    /**
     * Read only by the thread online scorers, to route between inline and agentic scoring, so through the service. The
     * estimate sums ClickHouse's own lengths of the stored payloads, so it is compared against the same spans written
     * this week, alone and together with the trace.
     */
    @Test
    void spansSizeOfTracesCountsAFarFutureSpan() {
        var trace = fixtures.createFarFutureTrace();
        var copy = fixtures.createThisWeekCopy(trace);
        var expectedSize = spansSize(Set.of(copy.trace().id()));
        assertThat(expectedSize).isPositive();

        var actualSize = spansSize(Set.of(trace.trace().id()));
        var actualSizeWithCopy = spansSize(Set.of(trace.trace().id(), copy.trace().id()));

        assertThat(actualSize).isEqualTo(expectedSize);
        assertThat(actualSizeWithCopy).isEqualTo(2 * expectedSize);
    }

    private long spansSize(Set<UUID> traceIds) {
        return spanService.getSpansSizeByTraceIds(traceIds)
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, USER, WORKSPACE_ID))
                .block();
    }

    /** The stats helpers price spans from their model and usage; the fixture's spans carry an explicit cost instead. */
    private List<ProjectStats.ProjectStatItem<?>> withExplicitCost(List<ProjectStats.ProjectStatItem<?>> stats,
            BigDecimal averageCost, BigDecimal totalCost) {
        var costs = Map.of(StatsMapper.TOTAL_ESTIMATED_COST, averageCost,
                StatsMapper.TOTAL_ESTIMATED_COST_SUM, totalCost);
        return stats
                .stream().<ProjectStats.ProjectStatItem<?>>map(stat -> Optional
                        .ofNullable(costs.get(stat.getName())).<ProjectStats.ProjectStatItem<?>>map(
                                cost -> ProjectStats.AvgValueStat.builder()
                                        .name(stat.getName())
                                        .value(cost.doubleValue())
                                        .type(ProjectStats.StatsType.AVG)
                                        .build())
                        .orElse(stat))
                .toList();
    }

    private Trace expectedTrace(FarFutureTrace trace) {
        return trace.trace().toBuilder()
                .projectId(trace.projectId())
                .usage(trace.totalUsage())
                .totalEstimatedCost(trace.totalCost())
                .spanCount(trace.spans().size())
                .build();
    }

    /** One trace, so the per-trace averages are the trace's own totals. */
    private Experiment expectedExperiment(Experiment experiment, FarFutureTrace trace) {
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

    private void assertExperiment(Experiment actual, Experiment expected) {
        assertThat(actual)
                .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                        .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                        .build())
                .ignoringFields(EXPERIMENT_IGNORED_FIELDS)
                .isEqualTo(expected);
    }

    /** One experiment item over one trace: every average and percentile is that trace's own value. */
    private List<ProjectStats.ProjectStatItem<?>> expectedExperimentItemsStats(FarFutureTrace trace) {
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

    private TraceThread expectedThread(FarFutureTrace trace) {
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

    private List<TraceThreadFilter> threadIdFilter(String threadId) {
        return List.of(TraceThreadFilter.builder()
                .field(TraceThreadField.ID)
                .operator(Operator.EQUAL)
                .value(threadId)
                .build());
    }

    /** The partitioned successor has no public API before the cutover, so it is installed the way the cutover does. */
    private void installPartitionedSpans(DatabaseAnalyticsFactory databaseAnalyticsFactory) {
        TransactionTemplateAsync.create(databaseAnalyticsFactory.build())
                .nonTransaction(connection -> Mono.from(connection
                        .createStatement("EXCHANGE TABLES spans AND spans_local_v2 ON CLUSTER '{cluster}'")
                        .execute()))
                .block();
    }
}
