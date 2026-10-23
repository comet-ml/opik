package com.comet.opik.infrastructure;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.ProjectStatsSummary.ProjectStatsSummaryItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanSearchStreamRequest;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceSearchStreamRequest;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.metrics.KpiCardRequest;
import com.comet.opik.api.metrics.KpiCardResponse;
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
import com.comet.opik.api.resources.utils.resources.AnnotationQueuesResourceClient;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.spans.SpanAssertions;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.api.resources.v1.events.SpanWeeksListener;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.api.sorting.SpanSortingFactory;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesService;
import com.comet.opik.domain.retention.RetentionUtils;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.SpanWeeksReadFixtures.FarFutureTrace;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.AsyncUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions.EXPERIMENT_IGNORED_FIELDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The spans reads bounded by the span_weeks index ({@code spanWeeksReadEnabled}), keyed by traces or by a project,
 * return every span of their traces or project, a far-future one included, on the weekly-partitioned spans table: each
 * read is compared as a whole against what those spans add up to. Weeks are registered by {@code span_weeks_mv} alone
 * ({@link SpanWeeksListener} is disabled), and each fixture waits for them to be indexed unless it says otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpanWeeksReadBoundTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    /** The project stats a trace's spans make up, and the project they belong to. */
    private static final String[] PROJECT_STATS_COMPARED_FIELDS = {"projectId", "traceCount",
            "totalEstimatedCostSum", "usage"};

    private static final String[] OPTIMIZATION_IGNORED_FIELDS = {"datasetId", "createdAt", "lastUpdatedAt",
            "createdBy", "lastUpdatedBy", "studioConfig", "datasetName", "baselineObjectiveScore",
            "bestObjectiveScore", "baselineDuration", "bestDuration", "experimentScores", "projectName"};

    /**
     * The project size estimate is in GB rounded to two decimals, so a span shows in it only from 0.005 GB: with this
     * input, about 0.006 GB, the project is estimated at 0.01 GB, over the sorting limit of 0 GB this suite sets, and at
     * 0 without it. Short words, as a long run of base64 characters would be stripped as an attachment.
     */
    private static final String LARGE_INPUT = "opik ".repeat(1_200_000);

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
                                // Dynamic sorting only for projects estimated at 0 GB: all but the large-input one.
                                new CustomConfig("workspaceSettings.maxProjectSizeToAllowSorting", "0"),
                                // Finished experiments aggregate after this delay; its bucket lives twice as long, so
                                // the job's 5s tick always finds it.
                                new CustomConfig("experimentDenormalization.debounceDelay", "5s")))
                        .disableExtensions(List.of(SpanWeeksListener.class))
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private ProjectResourceClient projectResourceClient;
    private SpanResourceClient spanResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private OptimizationResourceClient optimizationResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private AnnotationQueuesResourceClient annotationQueuesResourceClient;
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
        this.projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        this.experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        this.optimizationResourceClient = new OptimizationResourceClient(clientSupport, baseUrl, factory);
        this.datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        this.annotationQueuesResourceClient = new AnnotationQueuesResourceClient(clientSupport, baseUrl);
        this.experimentAggregatesService = experimentAggregatesService;
        this.spanService = spanService;
        this.fixtures = new SpanWeeksReadFixtures(API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER, factory,
                projectResourceClient, traceResourceClient,
                spanResourceClient, datasetResourceClient, experimentResourceClient,
                spanService);
    }

    @Test
    void traceCountFilteredOnSpanCostCountsAFarFutureSpan() {
        var trace = fixtures.createFarFutureTrace();

        var actual = traceResourceClient.getTraces(trace.projectName(), null, API_KEY, WORKSPACE_NAME,
                fullCostFilter(trace), null, 10, Map.of());

        TraceAssertions.assertPage(actual, 1, 1, 1);
        TraceAssertions.assertTraces(actual.content(), List.of(expectedTrace(trace)), USER);
    }

    /** The trace KPI cost reads its span weeks from span_weeks in both query shapes: no filter, and a payload filter. */
    private Stream<Arguments> traceKpiCostCountsAFarFutureSpan() {
        return Stream.of(
                arguments(Named.of("no filter", (Function<FarFutureTrace, List<TraceFilter>>) trace -> List.of())),
                arguments(Named.of("input filter", (Function<FarFutureTrace, List<TraceFilter>>) trace -> List.of(
                        TraceFilter.builder()
                                .field(TraceField.INPUT)
                                .operator(Operator.CONTAINS)
                                .value(trace.trace().input().propertyStream().toList().getFirst().getValue().asText())
                                .build()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void traceKpiCostCountsAFarFutureSpan(Function<FarFutureTrace, List<TraceFilter>> filters) {
        var trace = fixtures.createFarFutureTrace();

        var actual = projectResourceClient.getKpiCards(trace.projectId(), KpiCardRequest.builder()
                .entityType(KpiCardRequest.EntityType.TRACES)
                .intervalStart(trace.trace().startTime().minus(Duration.ofHours(1)))
                .filters(JsonUtils.writeValueAsString(filters.apply(trace)))
                .build(), API_KEY, WORKSPACE_NAME);

        var durationMs = Duration.between(trace.trace().startTime(), trace.trace().endTime()).toNanos() / 1_000_000.0;
        var expected = KpiCardResponse.builder()
                .stats(List.of(
                        kpi(KpiCardResponse.KpiMetricType.COUNT, 1.0, 0.0),
                        kpi(KpiCardResponse.KpiMetricType.ERRORS, trace.trace().errorInfo() != null ? 100.0 : 0.0,
                                0.0),
                        kpi(KpiCardResponse.KpiMetricType.AVG_DURATION, durationMs, null),
                        kpi(KpiCardResponse.KpiMetricType.TOTAL_COST, trace.totalCost().doubleValue(), 0.0)))
                .build();
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringCollectionOrder()
                .withComparatorForType((a, b) -> Math.abs(a - b) <= 1e-6 ? 0 : Double.compare(a, b), Double.class)
                .isEqualTo(expected);
    }

    private static KpiCardResponse.KpiMetric kpi(KpiCardResponse.KpiMetricType type, Double current,
            Double previous) {
        return KpiCardResponse.KpiMetric.builder().type(type).currentValue(current).previousValue(previous).build();
    }

    /**
     * The trace reads whose spans sub-reads are bound to the trace's span weeks: by trace ids (the trace detail), and
     * by project, keyed on the page, on the trace id prefilter (a trace filter, with a sort on a span statistic so the
     * page is not keyed) and on the trace time window alone (the same sort, no filter), and the stream.
     */
    private Stream<Arguments> traceReadCountsAFarFutureSpan() {
        var costSort = List.of(SortingField.builder()
                .field(SortableFields.TOTAL_ESTIMATED_COST)
                .direction(Direction.DESC)
                .build());
        return Stream.of(
                arguments(Named.of("trace detail", (Function<FarFutureTrace, List<Trace>>) trace -> List.of(
                        traceResourceClient.getById(trace.trace().id(), WORKSPACE_NAME, API_KEY)))),
                arguments(Named.of("traces list, page",
                        (Function<FarFutureTrace, List<Trace>>) trace -> traceResourceClient
                                .getTraces(trace.projectName(), null, API_KEY, WORKSPACE_NAME, null, null, 10, Map.of())
                                .content())),
                arguments(Named.of("traces list, prefilter",
                        (Function<FarFutureTrace, List<Trace>>) trace -> traceResourceClient
                                .getTraces(trace.projectName(), null, API_KEY, WORKSPACE_NAME, nameFilter(trace),
                                        costSort, 10, Map.of())
                                .content())),
                arguments(Named.of("traces list, time window",
                        (Function<FarFutureTrace, List<Trace>>) trace -> traceResourceClient
                                .getTraces(trace.projectName(), null, API_KEY, WORKSPACE_NAME, null, costSort, 10,
                                        timeWindow(trace))
                                .content())),
                arguments(Named.of("trace stream", (Function<FarFutureTrace, List<Trace>>) trace -> traceResourceClient
                        .getStreamAndAssertContent(API_KEY, WORKSPACE_NAME, TraceSearchStreamRequest.builder()
                                .projectName(trace.projectName())
                                .build()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void traceReadCountsAFarFutureSpan(Function<FarFutureTrace, List<Trace>> read) {
        var trace = fixtures.createFarFutureTrace();

        var actual = read.apply(trace);

        TraceAssertions.assertTraces(actual, List.of(expectedTrace(trace)), USER);
    }

    /** An unknown trace has no project, so its detail looks the span weeks up across the workspace's projects. */
    @Test
    void traceDetailOfAnUnknownTraceIsNotFound() {
        try (var actualResponse = traceResourceClient.callGetById(ID_GENERATOR.generateId(), API_KEY, WORKSPACE_NAME,
                Map.of())) {
            assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }

    /**
     * The trace stats of a project, all-time, over a trace time window, and filtered on the trace's full cost, which
     * also scopes the feedback-score stats to the filtered traces: the trace's own score is averaged only if the
     * far-future span is read.
     */
    private Stream<Arguments> traceStatsCountAFarFutureSpan() {
        return Stream.of(
                arguments(Named.of("project", (Function<FarFutureTrace, ProjectStats>) trace -> traceResourceClient
                        .getTraceStats(null, trace.projectId(), API_KEY, WORKSPACE_NAME, null, Map.of()))),
                arguments(Named.of("time window", (Function<FarFutureTrace, ProjectStats>) trace -> traceResourceClient
                        .getTraceStats(null, trace.projectId(), API_KEY, WORKSPACE_NAME, null, timeWindow(trace)))),
                arguments(Named.of("filtered", (Function<FarFutureTrace, ProjectStats>) trace -> traceResourceClient
                        .getTraceStats(null, trace.projectId(), API_KEY, WORKSPACE_NAME, fullCostFilter(trace),
                                Map.of()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void traceStatsCountAFarFutureSpan(Function<FarFutureTrace, ProjectStats> read) {
        var trace = fixtures.createFarFutureTrace();
        var score = factory.manufacturePojo(FeedbackScore.class);
        traceResourceClient.feedbackScore(trace.trace().id(), score, WORKSPACE_NAME, API_KEY);

        var actual = read.apply(trace);

        var expectedTrace = expectedStatsTrace(trace).toBuilder().feedbackScores(List.of(score)).build();
        TraceAssertions.assertStats(actual.stats(), StatsUtils.getProjectTraceStatItems(List.of(expectedTrace)));
    }

    /** The span weeks of a queue's stats are looked up by the queue's traces. */
    @Test
    void annotationQueueTraceStatsCountAFarFutureSpan() {
        var trace = fixtures.createFarFutureTrace();
        var queue = factory.manufacturePojo(AnnotationQueue.class).toBuilder()
                .projectId(trace.projectId())
                .scope(AnnotationQueue.AnnotationScope.TRACE)
                .build();
        var queueId = annotationQueuesResourceClient.createAnnotationQueue(queue, API_KEY, WORKSPACE_NAME,
                HttpStatus.SC_CREATED);
        annotationQueuesResourceClient.addItemsToAnnotationQueue(queueId, Set.of(trace.trace().id()), API_KEY,
                WORKSPACE_NAME, HttpStatus.SC_NO_CONTENT);

        var actual = traceResourceClient.getTraceStats(null, trace.projectId(), API_KEY, WORKSPACE_NAME, null,
                Map.of("annotation_queue_id", queueId.toString()));

        TraceAssertions.assertStats(actual.stats(),
                StatsUtils.getProjectTraceStatItems(List.of(expectedStatsTrace(trace))));
    }

    /** The projects page lists the newest projects first, so the trace's own project is on its first page. */
    @Test
    void projectsStatsCountAFarFutureSpan() {
        var trace = fixtures.createFarFutureTrace();

        var actual = projectResourceClient.getProjectStatsSummary(null, API_KEY, WORKSPACE_NAME).content();

        var expected = ProjectStatsSummaryItem.builder()
                .projectId(trace.projectId())
                .traceCount(1L)
                .totalEstimatedCostSum(trace.totalCost().doubleValue())
                .usage(trace.totalUsage().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().doubleValue())))
                .build();
        assertThat(actual)
                .filteredOn(item -> trace.projectId().equals(item.projectId()))
                .singleElement()
                .usingRecursiveComparison()
                .comparingOnlyFields(PROJECT_STATS_COMPARED_FIELDS)
                .withComparatorForType(StatsUtils::compareDoubles, Double.class)
                .isEqualTo(expected);
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

    /**
     * A search keeps the page pushdown, whose spans reads take every trace of the page's threads, the ones the search
     * drops included: the search matches the trace by id, so its copy in the same thread must not count.
     */
    @Test
    void threadListSearchCountsOnlyTheSpansOfTheMatchingTrace() {
        var trace = fixtures.createFarFutureTrace();
        fixtures.createThisWeekCopy(trace, trace.trace().threadId());

        var expectedThreads = List.of(expectedThread(trace));
        // Threads are materialised from their traces asynchronously.
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var actualThreads = traceResourceClient.getTraceThreads(trace.projectId(), null, API_KEY, WORKSPACE_NAME,
                    null, null, Map.of("search", trace.trace().id().toString())).content();
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

    /**
     * The spans of a project and their count, bound to the project's span weeks: the default page, the span id
     * prefilter of both statements (a span filter with an is-empty feedback score filter), the deduped spans of a
     * custom sort, a search without and with the prefilter, and a span time window that ends past the far-future span.
     */
    private Stream<Arguments> projectSpansPageCountsAFarFutureSpan() {
        return Stream.of(
                arguments(Named.of("default", (Function<FarFutureTrace, Span.SpanPage>) trace -> findProjectSpans(
                        trace, null, null, null, null, null))),
                arguments(Named.of("filtered", (Function<FarFutureTrace, Span.SpanPage>) trace -> findProjectSpans(
                        trace, prefilteredFilters(), null, null, null, null))),
                arguments(Named.of("custom sort", (Function<FarFutureTrace, Span.SpanPage>) trace -> findProjectSpans(
                        trace, null, List.of(SortingField.builder()
                                .field(SortableFields.ID)
                                .direction(Direction.DESC)
                                .build()),
                        null, null, null))),
                arguments(Named.of("search", (Function<FarFutureTrace, Span.SpanPage>) trace -> findProjectSpans(
                        trace, null, null, null, null, trace.trace().id().toString()))),
                arguments(Named.of("search, filtered",
                        (Function<FarFutureTrace, Span.SpanPage>) trace -> findProjectSpans(trace,
                                prefilteredFilters(), null, null, null, trace.trace().id().toString()))),
                arguments(Named.of("time window", (Function<FarFutureTrace, Span.SpanPage>) trace -> {
                    var window = spanTimeWindow(trace);
                    return findProjectSpans(trace, null, null, window.get("from_time"), window.get("to_time"), null);
                })));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void projectSpansPageCountsAFarFutureSpan(Function<FarFutureTrace, Span.SpanPage> read) {
        var trace = fixtures.createFarFutureTrace();

        var actualPage = read.apply(trace);

        var expectedSpans = fixtures.expectedSpans(trace.spans());
        SpanAssertions.assertPage(actualPage, 1, expectedSpans.size(), expectedSpans.size());
        SpanAssertions.assertSpan(actualPage.content(), expectedSpans, USER);
    }

    /** The cursor sits past the far-future span, which comes first in the stream's id order. */
    private Stream<Arguments> projectSpansStreamIncludesAFarFutureSpan() {
        return Stream.of(
                arguments(Named.of("project",
                        (Function<FarFutureTrace, SpanSearchStreamRequest>) trace -> SpanSearchStreamRequest
                                .builder()
                                .projectName(trace.projectName())
                                .build())),
                arguments(Named.of("cursor",
                        (Function<FarFutureTrace, SpanSearchStreamRequest>) trace -> SpanSearchStreamRequest
                                .builder()
                                .projectName(trace.projectName())
                                .lastRetrievedId(ID_GENERATOR.getTimeOrderedEpoch(
                                        farFutureSpanTime(trace).plus(Duration.ofDays(1)).toEpochMilli()))
                                .build())),
                arguments(Named.of("time window", (Function<FarFutureTrace, SpanSearchStreamRequest>) trace -> {
                    var window = spanTimeWindow(trace);
                    return SpanSearchStreamRequest.builder()
                            .projectName(trace.projectName())
                            .fromTime(Instant.parse(window.get("from_time")))
                            .toTime(Instant.parse(window.get("to_time")))
                            .build();
                })));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void projectSpansStreamIncludesAFarFutureSpan(Function<FarFutureTrace, SpanSearchStreamRequest> request) {
        var trace = fixtures.createFarFutureTrace();

        var actualSpans = spanResourceClient.getStreamAndAssertContent(API_KEY, WORKSPACE_NAME, request.apply(trace));

        var expectedSpans = fixtures.expectedSpans(trace.spans());
        SpanAssertions.assertSpan(actualSpans, expectedSpans, USER);
    }

    /**
     * The span stats of a project, all-time, over a span time window, filtered, and searched. A filter or a search also
     * scopes the feedback-score stats to the matching spans, so the far-future span's score is averaged only if that
     * span is read there too.
     */
    private Stream<Arguments> projectSpanStatsCountAFarFutureSpan() {
        return Stream.of(
                arguments(Named.of("project", (Function<FarFutureTrace, ProjectStats>) trace -> spanResourceClient
                        .getSpansStats(trace.projectName(), null, null, API_KEY, WORKSPACE_NAME, Map.of()))),
                arguments(Named.of("time window", (Function<FarFutureTrace, ProjectStats>) trace -> spanResourceClient
                        .getSpansStats(trace.projectName(), null, null, API_KEY, WORKSPACE_NAME,
                                spanTimeWindow(trace)))),
                arguments(Named.of("filtered", (Function<FarFutureTrace, ProjectStats>) trace -> spanResourceClient
                        .getSpansStats(trace.projectName(), null, positiveCostFilter(), API_KEY, WORKSPACE_NAME,
                                Map.of()))),
                arguments(Named.of("search", (Function<FarFutureTrace, ProjectStats>) trace -> spanResourceClient
                        .getSpansStats(trace.projectName(), null, null, API_KEY, WORKSPACE_NAME,
                                Map.of("search", trace.trace().id().toString())))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void projectSpanStatsCountAFarFutureSpan(Function<FarFutureTrace, ProjectStats> read) {
        var trace = fixtures.createFarFutureTrace();
        var farFutureSpan = trace.spans().getLast();
        var score = factory.manufacturePojo(FeedbackScore.class);
        spanResourceClient.feedbackScore(farFutureSpan.id(), score, WORKSPACE_NAME, API_KEY);

        var actual = read.apply(trace);

        var expectedSpans = trace.spans().stream()
                .map(span -> span.id().equals(farFutureSpan.id())
                        ? span.toBuilder().feedbackScores(List.of(score)).build()
                        : span)
                .toList();
        var expectedStats = withExplicitCost(StatsUtils.getProjectSpanStatItems(expectedSpans),
                trace.totalCost().divide(BigDecimal.valueOf(expectedSpans.size())), trace.totalCost());
        TraceAssertions.assertStats(actual.stats(), expectedStats);
    }

    @Test
    void spansByTraceIdsIncludeAFarFutureSpan() {
        var trace = fixtures.createFarFutureTrace();

        var actualSpanIds = fixtures.getSpanIdsOfDatasetItemFromTrace(trace.trace().id());

        var expectedSpanIds = trace.spans().stream().map(Span::id).toList();
        assertThat(actualSpanIds).containsExactlyInAnyOrderElementsOf(expectedSpanIds);
    }

    /**
     * The project size estimate, read by the traces and spans lists to gate dynamic sorting: a far-future span with the
     * large input puts the project over the limit, which empties both pages' sortable fields, and with a small input
     * the project stays under it.
     */
    private Stream<Arguments> projectSizeCountsAFarFutureSpan() {
        var largeInput = JsonNodeFactory.instance.objectNode().put("text", LARGE_INPUT);
        return Stream.of(
                arguments(Named.of("over the sorting limit", (UnaryOperator<Span>) span -> span.toBuilder()
                        .input(largeInput)
                        .build()), List.of(), List.of()),
                arguments(Named.of("under the sorting limit", UnaryOperator.<Span>identity()),
                        new SpanSortingFactory().getSortableFields(), new TraceSortingFactory().getSortableFields()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void projectSizeCountsAFarFutureSpan(UnaryOperator<Span> farFutureSpan, List<String> expectedSpansSortableBy,
            List<String> expectedTracesSortableBy) {
        var trace = fixtures.createFarFutureTrace(farFutureSpan);

        var actualSpansPage = findProjectSpans(trace, null, null, null, null, null);
        var actualTracesPage = traceResourceClient.getTraces(trace.projectName(), null, API_KEY, WORKSPACE_NAME, null,
                null, 10, Map.of());

        var expectedSpans = fixtures.expectedSpans(trace.spans());
        SpanAssertions.assertPage(actualSpansPage, 1, expectedSpans.size(), expectedSpans.size());
        SpanAssertions.assertSpan(actualSpansPage.content(), expectedSpans, USER);
        assertThat(actualSpansPage.sortableBy()).isEqualTo(expectedSpansSortableBy);
        TraceAssertions.assertPage(actualTracesPage, 1, 1, 1);
        TraceAssertions.assertTraces(actualTracesPage.content(), List.of(expectedTrace(trace)), USER);
        assertThat(actualTracesPage.sortableBy()).isEqualTo(expectedTracesSortableBy);
    }

    @Test
    void spanFeedbackScoreNamesOfATypeIncludeANameScoredOnlyOnAFarFutureSpan() {
        var trace = fixtures.createFarFutureTrace();
        var farFutureSpan = trace.spans().getLast();
        var score = factory.manufacturePojo(FeedbackScore.class);
        spanResourceClient.feedbackScore(farFutureSpan.id(), score, WORKSPACE_NAME, API_KEY);

        var actualNames = spanResourceClient.getFeedbackScoreNames(trace.projectId(), farFutureSpan.type(), API_KEY,
                WORKSPACE_NAME);

        var expectedNames = FeedbackScoreNames.builder()
                .scores(List.of(FeedbackScoreNames.ScoreName.builder().name(score.name()).build()))
                .build();
        assertThat(actualNames).isEqualTo(expectedNames);
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

    private Span.SpanPage findProjectSpans(FarFutureTrace trace, List<SpanFilter> filters, List<SortingField> sorting,
            String fromTime, String toTime, String search) {
        return spanResourceClient.findSpans(WORKSPACE_NAME, API_KEY, trace.projectName(), null, 1, 10, null, null,
                filters, sorting, null, fromTime, toTime, search);
    }

    /** Every fixture span has a cost. */
    private List<SpanFilter> positiveCostFilter() {
        return List.of(SpanFilter.builder()
                .field(SpanField.TOTAL_ESTIMATED_COST)
                .operator(Operator.GREATER_THAN)
                .value("0")
                .build());
    }

    /**
     * No span has a score, so the is-empty feedback score filter keeps both. It disables the page-keyed aggregates,
     * and with the span filter turns on the span id prefilter of the page and of the count.
     */
    private List<SpanFilter> prefilteredFilters() {
        return List.of(positiveCostFilter().getFirst(), SpanFilter.builder()
                .field(SpanField.FEEDBACK_SCORES)
                .operator(Operator.IS_EMPTY)
                .key("score-" + RandomStringUtils.secure().nextAlphanumeric(32))
                .value("")
                .build());
    }

    /** From an hour before the trace to a day past the far-future span: both spans, and their weeks at either end. */
    private Map<String, String> spanTimeWindow(FarFutureTrace trace) {
        return Map.of(
                "from_time", trace.trace().startTime().minus(Duration.ofHours(1)).toString(),
                "to_time", farFutureSpanTime(trace).plus(Duration.ofDays(1)).toString());
    }

    private Instant farFutureSpanTime(FarFutureTrace trace) {
        return RetentionUtils.extractInstant(trace.spans().getLast().id());
    }

    private List<TraceFilter> nameFilter(FarFutureTrace trace) {
        return List.of(TraceFilter.builder()
                .field(TraceField.NAME)
                .operator(Operator.EQUAL)
                .value(trace.trace().name())
                .build());
    }

    /** The trace reaches its full cost only with the far-future span, so the filter keeps it only if that is read. */
    private List<TraceFilter> fullCostFilter(FarFutureTrace trace) {
        return List.of(TraceFilter.builder()
                .field(TraceField.TOTAL_ESTIMATED_COST)
                .operator(Operator.GREATER_THAN_EQUAL)
                .value(trace.totalCost().toPlainString())
                .build());
    }

    /** A trace time window around the trace, which the far-future span sits far outside of. */
    private Map<String, String> timeWindow(FarFutureTrace trace) {
        var traceTime = trace.trace().startTime();
        return Map.of(
                "from_time", traceTime.minus(Duration.ofHours(1)).toString(),
                "to_time", traceTime.plus(Duration.ofMinutes(5)).toString());
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

    private Trace expectedStatsTrace(FarFutureTrace trace) {
        return expectedTrace(trace).toBuilder()
                .llmSpanCount((int) trace.spans().stream().filter(span -> span.type() == SpanType.llm).count())
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
