package com.comet.opik.infrastructure;

import com.comet.opik.api.Comment;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatchUpdate;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.Trace;
import com.comet.opik.api.metrics.KpiCardRequest;
import com.comet.opik.api.metrics.KpiCardResponse;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
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
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.spans.SpanAssertions;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.http.HttpStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * That each of the eleven span-id reads OPIK-8361 bounded actually prunes weekly partitions, observed on the
 * successor table the bounds are for: {@code spans_local_v2} is swapped in under {@code spans} before the app starts,
 * so every read runs against the weekly partitioning it will meet after the cutover.
 *
 * <p>The evidence is the {@code partitions} column ClickHouse records in {@code system.query_log} for the statement
 * each site sent, so nothing here parses or rewrites SQL. The filler weeks are built so only partition pruning can
 * exclude them: each holds two spans of the tested project on different traces, which leaves the primary key unable
 * to rule the part out by id. A bounded read therefore touches only its own week, and an unbounded one, which is what
 * an id past the 2300 ceiling falls back to, touches every filler week. That second case is also what shows the
 * first one is not passing for another reason.
 *
 * <p>The trace stats' {@code scored_span_ids} is covered here too: its bound is a subquery over the scored span ids
 * rather than a set bound from Java, so it has no fallback to observe, and instead is shown to read only the weeks of
 * the spans that carry scores, far-future and past-ceiling ones included, while returning every one of their scores.
 *
 * <p>Rows, including far-future ids on the legacy table, are {@link SpansReadPathWeekBoundTest}'s job.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpansReadPathPartitionPruningTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    /** One project for the filler and every tested span, so no read can exclude the filler by project. */
    private static final String PROJECT_NAME = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    /** Where {@code id_at} saturates, so no week set can be derived and the read runs unbounded. */
    private static final Instant PAST_CEILING_ID_AT = LocalDate.of(2300, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

    private static final LocalDate THIS_MONDAY = LocalDate.now(ZoneOffset.UTC)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    private static final String THIS_WEEK = yyyymmdd(THIS_MONDAY);
    private static final List<LocalDate> FILLER_MONDAYS = List.of(
            THIS_MONDAY.minusWeeks(1), THIS_MONDAY.minusWeeks(2), THIS_MONDAY.minusWeeks(3));
    private static final List<String> FILLER_WEEKS = FILLER_MONDAYS.stream()
            .map(SpansReadPathPartitionPruningTest::yyyymmdd)
            .toList();

    /** The {@code spans} partitions the latest statement of an op, mentioning the given span id, read. */
    private static final String SPANS_PARTITIONS_READ = """
            SELECT arrayStringConcat(arrayFilter(p -> startsWith(p, :prefix), partitions), ',')
            FROM system.query_log
            WHERE log_comment LIKE concat(:op, ':%')
            AND type = 'QueryFinish'
            AND query LIKE concat('%', :span_id, '%')
            ORDER BY event_time_microseconds DESC
            LIMIT 1
            """;

    private static final String LAST_SPAN_SEARCH = """
            SELECT query
            FROM system.query_log
            WHERE log_comment LIKE concat(:query_name, ':%')
            AND type = 'QueryFinish'
            AND query LIKE concat('%', :token, '%')
            ORDER BY event_time_microseconds DESC
            LIMIT 1
            """;

    private static final String PARTITION_PREFIX = "%s.spans.".formatted(DATABASE_NAME);

    private static final String FAST_LOG_FLUSH_CONFIG = "clickhouse-fast-log-flush.xml";

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeperContainer)
            .withCopyFileToContainer(MountableFile.forClasspathResource(FAST_LOG_FLUSH_CONFIG),
                    "/etc/clickhouse-server/config.d/%s".formatted(FAST_LOG_FLUSH_CONFIG));
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
                                // Lets the filler and past-ceiling ids through ingestion, as in production.
                                new CustomConfig("uuidValidation.enabled", "false"),
                                // The successor's columns are non-nullable, as the cutover flips this with it.
                                new CustomConfig("databaseAnalyticsDataModel.spanColumnsNonNullable", "true")))
                        .build());
    }

    private SpanResourceClient spanResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private TransactionTemplateAsync template;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        this.datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        this.projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.template = template;
        // One batch, so each filler week is one part holding two traces the primary key cannot exclude by id.
        spanResourceClient.batchCreateSpans(FILLER_MONDAYS.stream()
                .flatMap(monday -> Stream.of(0, 1).map(_ -> newSpan(
                        monday.atTime(12, 0).toInstant(ZoneOffset.UTC), ID_GENERATOR.generateId())))
                .toList(), API_KEY, WORKSPACE_NAME);
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    /** Each trigger reaches its site through the API for an ingested span, and returns the id its statement names. */
    private Stream<Arguments> sites() {
        // Read at call time: @MethodSource runs before @BeforeAll wires the clients in.
        Function<Span, UUID> getById = span -> {
            spanResourceClient.getById(span.id(), WORKSPACE_NAME, API_KEY);
            return span.id();
        };
        // Span creation runs the partial lookup. Re-posting makes it find the stored row, so the read touches the
        // span's partition rather than being ruled out by the primary key before any partition.
        Function<Span, UUID> created = span -> {
            spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);
            return span.id();
        };
        Function<Span, UUID> update = span -> {
            updateTags(span);
            return span.id();
        };
        // A span comment runs the project lookup, and its CommentsCreated event the experiment-refs read.
        Function<Span, UUID> comment = span -> {
            try (var response = spanResourceClient.callAddSpanComment(span.id(),
                    Comment.builder().text("week-bound").build(), API_KEY, WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            }
            return span.id();
        };

        return Stream.of(
                // A get-by-id runs the target-projects read first, so one trigger covers both query names.
                arguments("get_spans_by_ids", getById),
                arguments("get_target_project_ids_for_spans", getById),
                arguments("get_partial_span_by_id", created),
                // INSERT reads a stored row only over a partial one: PATCH a fresh id in the span's week, then POST it.
                arguments("insert_span", (Function<Span, UUID>) span -> {
                    var id = ID_GENERATOR.getTimeOrderedEpoch(span.id().getMostSignificantBits() >>> 16);
                    var fresh = span.toBuilder().id(id).build();
                    updateTags(fresh);
                    spanResourceClient.createSpan(fresh, API_KEY, WORKSPACE_NAME);
                    return id;
                }),
                arguments("get_only_span_by_id", update),
                arguments("update_span", update),
                arguments("get_project_id_from_span", comment),
                arguments("get_experiment_refs_by_span_ids", comment),
                // A PATCH of an id nobody created yet, minted in the same week as the given span.
                arguments("partial_insert_span", (Function<Span, UUID>) span -> {
                    var id = ID_GENERATOR.getTimeOrderedEpoch(span.id().getMostSignificantBits() >>> 16);
                    updateTags(span.toBuilder().id(id).build());
                    return id;
                }),
                arguments("bulk_update_spans", (Function<Span, UUID>) span -> {
                    batchUpdateTags(span, Set.of(span.id()));
                    return span.id();
                }),
                arguments("get_span_workspace", (Function<Span, UUID>) span -> {
                    datasetResourceClient.createDatasetItems(datasetItemReferencing(span), WORKSPACE_NAME, API_KEY);
                    return span.id();
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sites")
    void aBoundedReadTouchesOnlyItsOwnWeek(String queryName, Function<Span, UUID> trigger) {
        // One instant for both the id and the expected week, so a run crossing Monday UTC still agrees
        var idAt = Instant.now();
        var id = trigger.apply(createSpan(idAt, ID_GENERATOR.generateId()));

        assertThat(spansPartitionsRead(queryName, id)).containsExactly(yyyymmdd(mondayOf(idAt)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sites")
    void anIdPastTheCeilingFallsBackToReadingEveryWeek(String queryName, Function<Span, UUID> trigger) {
        var id = trigger.apply(createSpan(PAST_CEILING_ID_AT, ID_GENERATOR.generateId()));

        assertThat(spansPartitionsRead(queryName, id)).containsAll(FILLER_WEEKS);
    }

    @Test
    void oneUnderivableIdDropsTheBoundForTheWholeBatch() {
        var traceId = ID_GENERATOR.generateId();
        var derivable = createSpan(Instant.now(), traceId);
        var underivable = createSpan(PAST_CEILING_ID_AT, traceId);

        batchUpdateTags(derivable, Set.of(derivable.id(), underivable.id()));

        assertThat(spansPartitionsRead("bulk_update_spans", derivable.id())).containsAll(FILLER_WEEKS);
    }

    private Stream<Arguments> scoredSpanWeeks() {
        var thisMonday = THIS_MONDAY.atStartOfDay().toInstant(ZoneOffset.UTC);
        // Anywhere a bad clock can put an id: past 2106 but inside DateTime64, and past 2300 where it saturates.
        var farFuture = randomInstant(Instant.parse("2107-01-01T00:00:00Z"), Instant.parse("2299-12-01T00:00:00Z"));
        var pastCeiling = randomInstant(PAST_CEILING_ID_AT, Instant.parse("2500-01-01T00:00:00Z"));
        return Stream.of(
                arguments(Named.of("this monday", thisMonday), THIS_WEEK),
                // The last millisecond of the previous week lands in the first filler week.
                arguments(Named.of("previous sunday", thisMonday.minusMillis(1)), FILLER_WEEKS.getFirst()),
                arguments(Named.of("far future", farFuture), yyyymmdd(farFuture.atZone(ZoneOffset.UTC).toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))),
                arguments(Named.of("past ceiling", pastCeiling), "22991225"));
    }

    private static Instant randomInstant(Instant fromInclusive, Instant toExclusive) {
        return Instant.ofEpochSecond(
                RandomUtils.secure().randomLong(fromInclusive.getEpochSecond(), toExclusive.getEpochSecond()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scoredSpanWeeks")
    void traceStatsReadOnlyTheWeekOfTheScoredSpan(Instant idAt, String expectedWeek) {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        // Stats are scoped by the project's traces, so the scored span needs one to hang off.
        var traceId = traceResourceClient.createTrace(factory.manufacturePojo(Trace.class).toBuilder()
                .id(ID_GENERATOR.generateId())
                .projectName(projectName)
                .feedbackScores(null)
                .usage(null)
                .build(), API_KEY, WORKSPACE_NAME);
        // Unscored spans in every filler week, so only the bound can keep the read out of them.
        spanResourceClient.batchCreateSpans(FILLER_MONDAYS.stream()
                .flatMap(monday -> Stream.of(0, 1).map(_ -> newSpan(
                        monday.atTime(12, 0).toInstant(ZoneOffset.UTC), ID_GENERATOR.generateId())
                        .toBuilder().projectName(projectName).build()))
                .toList(), API_KEY, WORKSPACE_NAME);
        var span = newSpan(idAt, traceId).toBuilder().projectName(projectName).build();
        spanResourceClient.batchCreateSpans(List.of(span), API_KEY, WORKSPACE_NAME);
        var score = factory.manufacturePojo(FeedbackScore.class);
        spanResourceClient.feedbackScores(List.of(FeedbackScoreBatchItem.builder()
                .id(span.id())
                .projectName(projectName)
                .name(score.name())
                .value(score.value())
                .source(ScoreSource.SDK)
                .build()), API_KEY, WORKSPACE_NAME);
        var expected = traceResourceClient.getById(traceId, WORKSPACE_NAME, API_KEY).toBuilder()
                .spanFeedbackScores(List.of(FeedbackScore.builder().name(score.name()).value(score.value()).build()))
                .build();

        var stats = traceResourceClient.getTraceStats(null, projectId, API_KEY, WORKSPACE_NAME, null, Map.of());

        TraceAssertions.assertStats(stats.stats(), StatsUtils.getProjectTraceStatItems(List.of(expected)));
        assertThat(spansPartitionsRead("get_trace_stats_feedback_scores", projectId))
                .as("partitions read for span %s (id_at %s, week %s)", span.id(), idAt, expectedWeek)
                .containsExactly(expectedWeek);
    }

    @Test
    void traceStatsCountSpansInWeeksOtherThanTheirTrace() {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var now = Instant.now();
        var traceId = traceResourceClient.createTrace(factory.manufacturePojo(Trace.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(now.toEpochMilli()))
                .projectName(projectName)
                .feedbackScores(null)
                .usage(null)
                .build(), API_KEY, WORKSPACE_NAME);
        // The trace is in the window, one of its spans two weeks before it: the bound must still read that week.
        spanResourceClient.batchCreateSpans(List.of(
                newSpan(now, traceId).toBuilder().projectName(projectName).build(),
                newSpan(FILLER_MONDAYS.get(1).atTime(12, 0).toInstant(ZoneOffset.UTC), traceId).toBuilder()
                        .projectName(projectName).build()),
                API_KEY, WORKSPACE_NAME);
        // Spans either side of the trace-id window, each week one part, so the primary key cannot exclude it.
        spanResourceClient.batchCreateSpans(Stream.of(FILLER_MONDAYS.get(0), FILLER_MONDAYS.get(2))
                .flatMap(monday -> Stream.of(now.minus(Duration.ofHours(2)), now.plus(Duration.ofHours(1)))
                        .map(traceAt -> newSpan(monday.atTime(12, 0).toInstant(ZoneOffset.UTC),
                                ID_GENERATOR.getTimeOrderedEpoch(traceAt.toEpochMilli()))
                                .toBuilder().projectName(projectName).build()))
                .toList(), API_KEY, WORKSPACE_NAME);
        var expected = traceResourceClient.getById(traceId, WORKSPACE_NAME, API_KEY);

        var stats = traceResourceClient.getTraceStats(null, projectId, API_KEY, WORKSPACE_NAME, null, Map.of(
                "from_time", now.minus(Duration.ofHours(1)).toString(),
                "to_time", now.plus(Duration.ofMinutes(5)).toString()));

        TraceAssertions.assertStats(stats.stats(), StatsUtils.getProjectTraceStatItems(List.of(expected)));
    }

    @Test
    void kpiCostCountsAFarFutureSpanOfATraceInTheWindow() {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var now = Instant.now();
        var traceId = traceResourceClient.createTrace(factory.manufacturePojo(Trace.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(now.toEpochMilli()))
                .projectName(projectName)
                .startTime(now)
                .endTime(now.plusMillis(100))
                .errorInfo(null)
                .feedbackScores(null)
                .usage(null)
                .build(), API_KEY, WORKSPACE_NAME);
        // A span with a bad-clock id lands in a far-future week: only the span-weeks bound, not "now", still reads it.
        spanResourceClient.batchCreateSpans(List.of(
                newSpan(now, traceId).toBuilder().projectName(projectName)
                        .totalEstimatedCost(new BigDecimal("1.25")).build(),
                newSpan(Instant.parse("2201-08-30T03:18:08Z"), traceId).toBuilder().projectName(projectName)
                        .totalEstimatedCost(new BigDecimal("2.5")).build()),
                API_KEY, WORKSPACE_NAME);

        var actual = projectResourceClient.getKpiCards(projectId, KpiCardRequest.builder()
                .entityType(KpiCardRequest.EntityType.TRACES)
                .intervalStart(now.minus(Duration.ofHours(1)))
                .build(), API_KEY, WORKSPACE_NAME);

        var expected = KpiCardResponse.builder()
                .stats(List.of(
                        kpi(KpiCardResponse.KpiMetricType.COUNT, 1.0, 0.0),
                        kpi(KpiCardResponse.KpiMetricType.ERRORS, 0.0, 0.0),
                        kpi(KpiCardResponse.KpiMetricType.AVG_DURATION, 100.0, null),
                        kpi(KpiCardResponse.KpiMetricType.TOTAL_COST, 3.75, 0.0)))
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

    /** The searched spans and the responses, created once and shared by the span search tests below. */
    private record SpanSearch(String token, List<Span> expected, Span.SpanPage page, ProjectStats stats) {
    }

    private SpanSearch spanSearch;

    private synchronized SpanSearch spanSearch() {
        if (spanSearch != null) {
            return spanSearch;
        }
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var token = RandomStringUtils.secure().nextAlphanumeric(12);
        var fromTime = THIS_MONDAY.minusWeeks(10).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        // Two ordinary weeks and the far-future one a bad clock files spans under.
        var expected = Stream.of(THIS_MONDAY.minusWeeks(5), THIS_MONDAY, LocalDate.of(2199, 12, 30))
                .map(monday -> newSpan(monday.plusDays(2).atTime(12, 0).toInstant(ZoneOffset.UTC),
                        ID_GENERATOR.generateId()).toBuilder()
                        .projectName(projectName)
                        .name("searchable-" + token)
                        // newSpan leaves end_time unset, so the span has no duration; cost is derived from model/usage.
                        .duration(null)
                        .totalEstimatedCost(null)
                        .build())
                .sorted(Comparator.comparing(Span::id).reversed())
                .toList();
        spanResourceClient.batchCreateSpans(expected, API_KEY, WORKSPACE_NAME);
        var page = spanResourceClient.findSpans(WORKSPACE_NAME, API_KEY, projectName, null, 1, 10, null, null, null,
                null, null, fromTime, null, token);
        var stats = spanResourceClient.getSpansStats(projectName, null, null, API_KEY, WORKSPACE_NAME,
                Map.of("search", token, "from_time", fromTime));
        spanSearch = new SpanSearch(token, expected, page, stats);
        return spanSearch;
    }

    @Test
    void spanSearchBoundedToTheProjectsWeeksReturnsEveryMatch() {
        var search = spanSearch();

        assertThat(search.page().total()).isEqualTo(search.expected().size());
        SpanAssertions.assertSpan(search.page().content(), search.expected(), USER);
        TraceAssertions.assertStats(search.stats().stats(), StatsUtils.getProjectSpanStatItems(search.expected()));
    }

    @ParameterizedTest(name = "{0} carries the spans week hint")
    @ValueSource(strings = {"find_spans_by_project_id", "count_spans_by_project_id", "get_span_stats",
            "get_span_stats_feedback_scores"})
    void spanSearchStatementCarriesTheWeekHint(String queryName) {
        var expectedWeeks = spanSearch().expected().stream().map(Span::id)
                .map(SpansReadPathPartitionPruningTest::mondayOfId)
                .collect(Collectors.toSet());
        // Run each pre-pass from the statement the app executed, and compare with the weeks the written rows fall in.
        assertThat(weekSetsFromPrepasses(lastSpanSearch(queryName, spanSearch().token())))
                .as("the spans week hint in %s selects exactly the project's weeks", queryName)
                .isNotEmpty()
                .allSatisfy(weeks -> assertThat(weeks).containsExactlyInAnyOrderElementsOf(expectedWeeks));
    }

    @Test
    void spanSearchRunsOnce() {
        // The page re-reads its rows through the cached page-id scalar, so the search runs once.
        assertThat(lastSpanSearch("find_spans_by_project_id", spanSearch().token()))
                .contains("IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))")
                .doesNotContain("IN (SELECT id FROM page_ids)");
    }

    /**
     * Runs every week pre-pass in a logged statement on its own and returns the weeks each yields, so a test can
     * compare them with the weeks it derives from the rows it wrote. The logged statement carries its values inline.
     */
    private List<Set<String>> weekSetsFromPrepasses(String statement) {
        var sets = new ArrayList<Set<String>>();
        for (int at = statement.indexOf("SELECT DISTINCT toYYYYMMDD"); at >= 0; at = statement
                .indexOf("SELECT DISTINCT toYYYYMMDD", at + 1)) {
            int open = statement.lastIndexOf('(', at);
            int depth = 0, close = open;
            do {
                char c = statement.charAt(close++);
                depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            } while (depth > 0);
            var subquery = statement.substring(open + 1, close - 1);
            sets.add(new HashSet<>(template.nonTransaction(connection -> Mono.from(connection
                    .createStatement(subquery).execute())
                    .flatMapMany(result -> result.map((row, _) -> String.valueOf(row.get(0))))
                    .collectList()).block()));
        }
        return sets;
    }

    private static String mondayOfId(UUID id) {
        var monday = Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16).atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return "%04d%02d%02d".formatted(monday.getYear(), monday.getMonthValue(), monday.getDayOfMonth());
    }

    /** Polled: a statement's query_log row is written asynchronously, flushed every 200 ms here. */
    private String lastSpanSearch(String queryName, String token) {
        return Awaitility.await()
                .alias("query_log holds a " + queryName + " search for " + token)
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> template.nonTransaction(connection -> Mono.from(connection
                        .createStatement(LAST_SPAN_SEARCH)
                        .bind("token", token)
                        .bind("query_name", queryName)
                        .execute())
                        .flatMap(result -> Mono.from(result.map((row, _) -> row.get(0, String.class)))))
                        .block(), Objects::nonNull);
    }

    private void batchUpdateTags(Span span, Set<UUID> ids) {
        spanResourceClient.batchUpdateSpans(SpanBatchUpdate.builder()
                .ids(ids)
                .update(SpanUpdate.builder()
                        .traceId(span.traceId())
                        .parentSpanId(span.parentSpanId())
                        .tags(Set.of("week-bound"))
                        .build())
                .build(), API_KEY, WORKSPACE_NAME);
    }

    private void updateTags(Span span) {
        spanResourceClient.updateSpan(span.id(), SpanUpdate.builder()
                .projectName(span.projectName())
                .traceId(span.traceId())
                .parentSpanId(span.parentSpanId())
                .tags(Set.of("week-bound"))
                .build(), API_KEY, WORKSPACE_NAME);
    }

    private DatasetItemBatch datasetItemReferencing(Span span) {
        var item = factory.manufacturePojo(DatasetItem.class).toBuilder()
                .source(DatasetItemSource.SPAN)
                .spanId(span.id())
                .traceId(span.traceId())
                .experimentItems(null)
                .build();
        return DatasetItemBatch.builder()
                .datasetName("dataset-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32)))
                .items(List.of(item))
                .build();
    }

    private Span createSpan(Instant idAt, UUID traceId) {
        var span = newSpan(idAt, traceId);
        spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);
        return span;
    }

    private Span newSpan(Instant idAt, UUID traceId) {
        return factory.manufacturePojo(Span.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(idAt.toEpochMilli()))
                .projectName(PROJECT_NAME)
                .traceId(traceId)
                .startTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .endTime(null)
                .feedbackScores(null)
                .build();
    }

    /** Polled: a query's {@code query_log} row is written asynchronously, flushed every 200 ms here. */
    private Set<String> spansPartitionsRead(String queryName, UUID mentionedId) {
        var partitions = Awaitility.await()
                .alias("query_log holds a %s statement mentioning id %s".formatted(queryName, mentionedId))
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> template.nonTransaction(connection -> Mono.from(connection
                        .createStatement(SPANS_PARTITIONS_READ)
                        .bind("prefix", PARTITION_PREFIX)
                        .bind("op", queryName)
                        .bind("span_id", mentionedId.toString())
                        .execute())
                        .flatMap(result -> Mono.from(result.map((row, _) -> row.get(0, String.class)))))
                        .block(), Objects::nonNull);
        return Arrays.stream(partitions.split(","))
                .filter(partition -> !partition.isEmpty())
                .map(partition -> partition.substring(PARTITION_PREFIX.length()))
                .collect(Collectors.toSet());
    }

    private static LocalDate mondayOf(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static String yyyymmdd(LocalDate monday) {
        return monday.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
