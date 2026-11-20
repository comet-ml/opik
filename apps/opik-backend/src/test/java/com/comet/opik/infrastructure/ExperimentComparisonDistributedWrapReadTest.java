package com.comet.opik.infrastructure;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.ProjectStats.AvgValueStat;
import com.comet.opik.api.ProjectStats.CountValueStat;
import com.comet.opik.api.ProjectStats.PercentageValueStat;
import com.comet.opik.api.ProjectStats.ProjectStatItem;
import com.comet.opik.api.ProjectStats.StatsType;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.filter.ExperimentsComparisonValidKnownField;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.datasets.DatasetItemAssertions;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.TextNode;
import com.redis.testcontainers.RedisContainer;
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions.EXPERIMENT_ITEMS_IGNORED_FIELDS;
import static com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions.assertExperimentResultsIgnoringFields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dataset experiment comparison read through the {@code Distributed} {@code traces} and {@code spans} the cutover
 * installs, with every filter shape it renders.
 *
 * <p>Each filter renders an {@code IN} subquery over the comparison's CTEs. One that ClickHouse ships to the shard
 * without those CTEs, as it does for a subquery in a {@code JOIN ... ON}, fails there with {@code UNKNOWN_TABLE}, and
 * only there, so the suites on the plain tables pass. Each shape is asserted on the page and on the stats it returns.
 *
 * <p>The experiment is never aggregated here, so the comparison reads its items from {@code experiment_items}: the
 * branch that reads {@code traces} and {@code spans}. Once aggregated, an experiment is served without reading either,
 * so no wrap affects it.
 *
 * <p>Dedicated, non-reused containers: the cutover's {@code EXCHANGE} and wraps destructively swap the live tables.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ExperimentComparisonDistributedWrapReadTest {

    private static final String API_KEY = "apiKey-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32));
    private static final String WORKSPACE_NAME = "workspace-%s"
            .formatted(RandomStringUtils.secure().nextAlphanumeric(32));
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32));

    private static final String SCORE_NAME = "score-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32));
    private static final String OUTPUT_KEY = "key%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32));
    private static final String DATA_KEY = "key%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32));
    private static final String USAGE_KEY = "key%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32));
    private static final String TOTAL_TOKENS = "total_tokens";

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private static final List<Double> PERCENTILES = List.of(0.50, 0.90, 0.99);

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
        installPostCutoverTopology(TransactionTemplateAsync.create(databaseAnalyticsFactory.build()));
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        // The data model flags production runs on the cut-over tables.
                        .customConfigs(Stream.of(
                                "databaseAnalyticsDataModel.traceColumnsNonNullable",
                                "databaseAnalyticsDataModel.tracesDistributedWrapEnabled",
                                "databaseAnalyticsDataModel.spanColumnsNonNullable",
                                "databaseAnalyticsDataModel.spansDistributedWrapEnabled",
                                "databaseAnalyticsDataModel.spanWeeksReadEnabled")
                                .map(key -> CustomConfig.builder().key(key).value("true").build())
                                .toList())
                        .build());
    }

    private DatasetResourceClient datasetResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;

    private Fixture fixture;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        fixture = seed();
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    /** Each shape selects the matching item, the other one or both, so a dropped filter and a failed read both show. */
    private Stream<FilterShape> filterShapes() {
        return Stream.of(
                FilterShape.builder()
                        .name("no filter")
                        .filters(List.of())
                        .selected(fixture.both())
                        .build(),
                FilterShape.builder()
                        .name("known trace field")
                        .filters(List.of(ExperimentsComparisonFilter.builder()
                                .field(ExperimentsComparisonValidKnownField.OUTPUT.getQueryParamField())
                                .operator(Operator.CONTAINS)
                                .value(fixture.matching().value())
                                .build()))
                        .selected(fixture.matchingOnly())
                        .build(),
                FilterShape.builder()
                        .name("dynamic trace field")
                        .filters(List.of(ExperimentsComparisonFilter.builder()
                                .field("output.%s".formatted(OUTPUT_KEY))
                                .type(FieldType.STRING)
                                .operator(Operator.CONTAINS)
                                .value(fixture.matching().value())
                                .build()))
                        .selected(fixture.matchingOnly())
                        .build(),
                FilterShape.builder()
                        .name("dataset item field")
                        .filters(List.of(ExperimentsComparisonFilter.builder()
                                .field("data.%s".formatted(DATA_KEY))
                                .type(FieldType.STRING)
                                .operator(Operator.CONTAINS)
                                .value(fixture.matching().value())
                                .build()))
                        .selected(fixture.matchingOnly())
                        .build(),
                FilterShape.builder()
                        .name("feedback score")
                        .filters(List.of(ExperimentsComparisonFilter.builder()
                                .field(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES.getQueryParamField())
                                .key(SCORE_NAME)
                                .operator(Operator.GREATER_THAN_EQUAL)
                                .value(fixture.matching().score().value().toPlainString())
                                .build()))
                        .selected(fixture.matchingOnly())
                        .build(),
                FilterShape.builder()
                        .name("feedback score is empty")
                        .filters(List.of(ExperimentsComparisonFilter.builder()
                                .field(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES.getQueryParamField())
                                .key(SCORE_NAME)
                                .operator(Operator.IS_EMPTY)
                                .value("")
                                .build()))
                        .selected(fixture.otherOnly())
                        .build());
    }

    @ParameterizedTest(name = "page: {0}")
    @MethodSource("filterShapes")
    void pageReturnsTheItemsTheFilterSelects(FilterShape shape) {
        var expectedDatasetItems = shape.selected().stream().map(Item::datasetItem).toList();
        var expectedExperimentItems = shape.selected().stream()
                .map(Item::experimentItem)
                .sorted(Comparator.comparing(ExperimentItem::id))
                .toList();

        var actualPage = datasetResourceClient.getDatasetItemsWithExperimentItems(fixture.datasetId(),
                List.of(fixture.experimentId()), null, shape.filters(), API_KEY, WORKSPACE_NAME);

        assertThat(actualPage.total()).isEqualTo(expectedDatasetItems.size());
        DatasetItemAssertions.assertDatasetItemsInAnyOrder(actualPage.content(),
                expectedDatasetItems.toArray(DatasetItem[]::new));
        var actualExperimentItems = actualPage.content().stream()
                .flatMap(datasetItem -> datasetItem.experimentItems().stream())
                .sorted(Comparator.comparing(ExperimentItem::id))
                .toList();
        assertExperimentResultsIgnoringFields(actualExperimentItems, expectedExperimentItems,
                EXPERIMENT_ITEMS_IGNORED_FIELDS);
    }

    @ParameterizedTest(name = "stats: {0}")
    @MethodSource("filterShapes")
    void statsCoverTheItemsTheFilterSelects(FilterShape shape) {
        var expectedStats = expectedStats(shape.selected());

        var actualStats = datasetResourceClient.getDatasetExperimentItemsStats(fixture.datasetId(),
                List.of(fixture.experimentId()), API_KEY, WORKSPACE_NAME, shape.filters());

        TraceAssertions.assertStats(actualStats.stats(), expectedStats);
    }

    /**
     * Two items, each a trace with one span: the matching one carries its value in its trace output and its dataset
     * item's data, and is the only one scored; the other carries a different value and no score.
     */
    private Fixture seed() {
        var dataset = DatasetResourceClient.buildDataset(factory);
        var datasetId = datasetResourceClient.createDataset(dataset, API_KEY, WORKSPACE_NAME);
        var experiment = experimentResourceClient.createPartialExperiment().datasetName(dataset.name()).build();
        var experimentId = experimentResourceClient.create(experiment, API_KEY, WORKSPACE_NAME);

        var matching = item(experiment.name(), experimentId, true);
        var other = item(experiment.name(), experimentId, false);
        var items = List.of(matching, other);
        datasetResourceClient.createDatasetItems(DatasetItemBatch.builder()
                .datasetId(datasetId)
                .items(items.stream().map(Item::datasetItem).toList())
                .build(), WORKSPACE_NAME, API_KEY);
        items.forEach(item -> traceResourceClient.createTrace(item.trace(), API_KEY, WORKSPACE_NAME));
        spanResourceClient.batchCreateSpans(items.stream().map(Item::span).toList(), API_KEY, WORKSPACE_NAME);
        traceResourceClient.feedbackScores(List.of(matching.score()), API_KEY, WORKSPACE_NAME);
        experimentResourceClient.createExperimentItem(items.stream()
                .map(item -> item.experimentItem().toBuilder()
                        .input(null)
                        .output(null)
                        .traceMetadata(null)
                        .feedbackScores(null)
                        .totalEstimatedCost(null)
                        .usage(null)
                        .executionPolicy(null)
                        .build())
                .collect(Collectors.toSet()), API_KEY, WORKSPACE_NAME);

        return Fixture.builder()
                .datasetId(datasetId)
                .experimentId(experimentId)
                .matching(awaitStored(matching))
                .other(awaitStored(other))
                .build();
    }

    /** One item, with the experiment item as the comparison returns it: resolved from its trace, span and score. */
    private Item item(String projectName, UUID experimentId, boolean scored) {
        var value = RandomStringUtils.secure().nextAlphanumeric(32);
        var datasetItem = DatasetResourceClient.buildDatasetItem(factory).toBuilder()
                .data(Map.of(DATA_KEY, TextNode.valueOf(value)))
                .build();
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .output(JsonUtils.readTree(Map.of(OUTPUT_KEY, value)))
                .build();
        var span = factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .traceId(trace.id())
                .parentSpanId(null)
                .feedbackScores(null)
                .totalEstimatedCost(BigDecimal.valueOf(RandomUtils.secure().randomInt(1, 100_000), 2))
                .usage(Map.of(
                        USAGE_KEY, RandomUtils.secure().randomInt(1, 10_000),
                        TOTAL_TOKENS, RandomUtils.secure().randomInt(1, 10_000)))
                .build();
        var score = scored
                ? factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                        .id(trace.id())
                        .projectName(projectName)
                        .name(SCORE_NAME)
                        .source(ScoreSource.SDK)
                        .build()
                : null;
        var experimentItem = factory.manufacturePojo(ExperimentItem.class).toBuilder()
                .id(ID_GENERATOR.generateId())
                .experimentId(experimentId)
                .datasetItemId(datasetItem.id())
                .traceId(trace.id())
                .input(trace.input())
                .output(trace.output())
                .traceMetadata(trace.metadata())
                .feedbackScores(scored
                        ? List.of(FeedbackScoreMapper.INSTANCE.toFeedbackScore(score).toBuilder()
                                .createdBy(USER)
                                .lastUpdatedBy(USER)
                                .build())
                        : null)
                .comments(null)
                .totalEstimatedCost(span.totalEstimatedCost())
                .usage(span.usage().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().longValue())))
                // Neither the dataset version nor the experiment sets one.
                .executionPolicy(ExecutionPolicy.DEFAULT)
                .createdBy(USER)
                .lastUpdatedBy(USER)
                .build();
        return Item.builder()
                .value(value)
                .datasetItem(datasetItem)
                .trace(trace)
                .span(span)
                .score(score)
                .experimentItem(experimentItem)
                .build();
    }

    /**
     * Inserts through the {@code Distributed} wrappers are queued and forwarded to the shard asynchronously, so the
     * item's trace and span are read back through them until they are there. The span is read by its trace, the read
     * the span weeks index bounds, so it also waits for the span's week to be indexed. The trace read yields the
     * duration the server derived.
     */
    private Item awaitStored(Item item) {
        var storedTrace = new AtomicReference<Trace>();
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    storedTrace.set(traceResourceClient.getById(item.trace().id(), WORKSPACE_NAME, API_KEY));
                    assertThat(spanResourceClient.getByTraceIdAndProject(item.trace().id(),
                            item.trace().projectName(), WORKSPACE_NAME, API_KEY).content())
                            .extracting(Span::id)
                            .containsExactly(item.span().id());
                });
        return item.toBuilder()
                .trace(storedTrace.get())
                .experimentItem(item.experimentItem().toBuilder().duration(storedTrace.get().duration()).build())
                .build();
    }

    private List<ProjectStatItem<?>> expectedStats(List<Item> selected) {
        var costs = selected.stream().map(item -> item.span().totalEstimatedCost()).toList();
        var durations = selected.stream().map(item -> item.trace().duration()).toList();
        var scores = selected.stream()
                .map(Item::score)
                .filter(Objects::nonNull)
                .map(FeedbackScoreBatchItem::value)
                .toList();
        var itemStats = Stream.of(
                countStat(StatsMapper.EXPERIMENT_ITEMS_COUNT, selected.size()),
                countStat(StatsMapper.TRACE_COUNT, selected.size()),
                avgStat(StatsMapper.TOTAL_ESTIMATED_COST, average(costs)),
                percentageStat(StatsMapper.TOTAL_ESTIMATED_COST, toDoubles(costs)),
                percentageStat(StatsMapper.DURATION, durations),
                avgStat(usageStat(USAGE_KEY), average(usage(selected, USAGE_KEY))),
                avgStat(usageStat(TOTAL_TOKENS), average(usage(selected, TOTAL_TOKENS))),
                percentageStat(usageStat(TOTAL_TOKENS), toDoubles(usage(selected, TOTAL_TOKENS))));
        if (scores.isEmpty()) {
            return itemStats.toList();
        }
        var scoreStat = "feedback_scores.%s".formatted(SCORE_NAME);
        var scoreStats = Stream.of(
                avgStat(scoreStat, average(scores)),
                percentageStat(scoreStat, toDoubles(scores)));
        return Stream.concat(itemStats, scoreStats).toList();
    }

    private List<BigDecimal> usage(List<Item> selected, String key) {
        return selected.stream().map(item -> BigDecimal.valueOf(item.span().usage().get(key))).toList();
    }

    private String usageStat(String key) {
        return "%s.%s".formatted(StatsMapper.USAGE, key);
    }

    private double average(List<BigDecimal> values) {
        return values.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 9, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private List<Double> toDoubles(List<BigDecimal> values) {
        return values.stream().map(BigDecimal::doubleValue).toList();
    }

    private CountValueStat countStat(String name, long value) {
        return CountValueStat.builder().name(name).value(value).type(StatsType.COUNT).build();
    }

    private AvgValueStat avgStat(String name, double value) {
        return AvgValueStat.builder().name(name).value(value).type(StatsType.AVG).build();
    }

    private PercentageValueStat percentageStat(String name, List<Double> values) {
        var quantiles = StatsUtils.calculateQuantiles(values, PERCENTILES);
        return PercentageValueStat.builder()
                .name(name)
                .value(PercentageValues.builder()
                        .p50(quantiles.get(0))
                        .p90(quantiles.get(1))
                        .p99(quantiles.get(2))
                        .build())
                .type(StatsType.PERCENTAGE)
                .build();
    }

    /**
     * The topology production runs: {@code traces} and {@code spans} cut over to their partitioned successors and
     * wrapped in {@code Distributed}, as the runbook does, before the app boots against it.
     */
    private void installPostCutoverTopology(TransactionTemplateAsync template) {
        PostCutoverTraces.migrate(clickHouseContainer);
        execute(template, """
                EXCHANGE TABLES spans AND spans_local_v2 ON CLUSTER '{cluster}'
                """);
        execute(template, """
                CREATE TABLE spans_dist ON CLUSTER '{cluster}' AS spans
                ENGINE = Distributed('{cluster}', '%s', 'spans_local', sipHash64(project_id))
                """.formatted(DATABASE_NAME));
        execute(template, """
                RENAME TABLE spans TO spans_local, spans_dist TO spans ON CLUSTER '{cluster}'
                """);
    }

    private void execute(TransactionTemplateAsync template, String sql) {
        template.nonTransaction(connection -> Mono.from(connection.createStatement(sql).execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated())))
                .block();
    }

    /** A filter shape and the items it selects. Named for the test display name. */
    @Builder(toBuilder = true)
    private record FilterShape(String name, List<ExperimentsComparisonFilter> filters, List<Item> selected) {

        @Override
        public String toString() {
            return name;
        }
    }

    /** One comparison item, the value its filters match it by, and its experiment item as the comparison reads it. */
    @Builder(toBuilder = true)
    private record Item(String value, DatasetItem datasetItem, Trace trace, Span span, FeedbackScoreBatchItem score,
            ExperimentItem experimentItem) {
    }

    @Builder(toBuilder = true)
    private record Fixture(UUID datasetId, UUID experimentId, Item matching, Item other) {

        List<Item> both() {
            return List.of(matching, other);
        }

        List<Item> matchingOnly() {
            return List.of(matching);
        }

        List<Item> otherOnly() {
            return List.of(other);
        }
    }
}
