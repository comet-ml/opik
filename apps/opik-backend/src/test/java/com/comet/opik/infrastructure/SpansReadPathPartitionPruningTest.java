package com.comet.opik.infrastructure;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Comment;
import com.comet.opik.api.DataPoint;
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
import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceSearchStreamRequest;
import com.comet.opik.api.Webhook;
import com.comet.opik.api.events.webhooks.MetricsAlertPayload;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.metrics.KpiCardRequest;
import com.comet.opik.api.metrics.KpiCardResponse;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
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
import com.comet.opik.api.resources.utils.resources.AlertResourceClient;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectMetricsResourceClient;
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
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpStatus;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
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
import reactor.core.publisher.Flux;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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

    /** The statement a search sent under one query name, told apart by the search token the test chose. */
    private static final String SEARCH_STATEMENT = """
            SELECT query_id, query
            FROM system.query_log
            WHERE log_comment LIKE concat(:query_name, ':%')
            AND type = 'QueryFinish'
            AND query LIKE concat('%', :token, '%')
            ORDER BY event_time_microseconds DESC
            LIMIT 1
            """;

    /** The partition-key selection ClickHouse logs for each read of a statement. */
    private static final String PARTITION_KEY_SELECTIONS = """
            SELECT message
            FROM system.text_log
            WHERE query_id = :query_id
            AND logger_name LIKE :logger
            AND message LIKE '%parts by partition key%'
            """;

    private static final Pattern PARTITION_KEY_SELECTION = Pattern
            .compile("Selected (\\d+)/(\\d+) parts by partition key");

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
    private ProjectMetricsResourceClient projectMetricsResourceClient;
    private TraceResourceClient traceResourceClient;
    private TransactionTemplateAsync template;
    private AlertResourceClient alertResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        this.datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        this.projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        this.projectMetricsResourceClient = new ProjectMetricsResourceClient(clientSupport, baseUrl);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.template = template;
        this.alertResourceClient = new AlertResourceClient(clientSupport);
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

    @ParameterizedTest(name = "open-ended: {0}")
    @ValueSource(booleans = {false, true})
    void threadCostChartReadsOnlyTheSpanWeeksOfItsWindow(boolean openEnded) {
        var seeded = seedThreadWithSpansInManyWeeks();
        var intervalStart = seeded.now().minus(Duration.ofHours(1));
        var intervalEnd = openEnded ? null : Instant.now();

        var response = projectMetricsResourceClient.getProjectMetrics(seeded.projectId(), ProjectMetricRequest.builder()
                .metricType(MetricType.THREAD_COST)
                .interval(TimeInterval.HOURLY)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), BigDecimal.class, API_KEY, WORKSPACE_NAME);

        var cost = response.results().stream()
                .flatMap(result -> result.data().stream())
                .map(DataPoint::value)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(cost).isEqualByComparingTo(openEnded ? "3.75" : "1.25");
        assertThat(spansPartitionsRead("ProjectMetrics_threadCost", seeded.projectId()))
                .containsExactlyInAnyOrderElementsOf(seeded.weeksRead(intervalStart, intervalEnd));
    }

    @ParameterizedTest(name = "open-ended: {0}")
    @ValueSource(booleans = {false, true})
    void threadKpiCostReadsOnlyTheSpanWeeksOfItsWindow(boolean openEnded) {
        var seeded = seedThreadWithSpansInManyWeeks();
        var intervalStart = seeded.now().minus(Duration.ofHours(1));
        var intervalEnd = openEnded ? null : Instant.now();

        var requestedAt = Instant.now();
        var response = projectResourceClient.getKpiCards(seeded.projectId(), KpiCardRequest.builder()
                .entityType(KpiCardRequest.EntityType.THREADS)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), API_KEY, WORKSPACE_NAME);
        var respondedAt = Instant.now();
        // The card also reads the period before the window, as long as the window. When open, the server sizes it
        // with its own now, somewhere between these two instants, so a Monday boundary between them makes either
        // week set right.
        var expectedWeekSets = Stream.of(requestedAt, respondedAt)
                .map(now -> seeded.weeksRead(
                        intervalStart
                                .minus(Duration.between(intervalStart, Objects.requireNonNullElse(intervalEnd, now))),
                        intervalEnd))
                .collect(Collectors.toSet());

        assertThat(response.stats())
                .filteredOn(stat -> stat.type() == KpiCardResponse.KpiMetricType.TOTAL_COST)
                .singleElement()
                .satisfies(stat -> assertThat(stat.currentValue()).isCloseTo(openEnded ? 3.75 : 1.25,
                        within(1e-6)));
        assertThat(spansPartitionsRead("KpiCards_getThreadKpiCards", seeded.projectId()))
                .isIn(expectedWeekSets);
    }

    private record SeededThread(UUID projectId, Instant now, Set<String> spanWeeks) {
        Set<String> weeksRead(Instant from, Instant to) {
            return spanWeeks.stream()
                    .filter(week -> week.compareTo(yyyymmdd(mondayOf(from))) >= 0)
                    .filter(week -> to == null || week.compareTo(yyyymmdd(mondayOf(to))) <= 0)
                    .collect(Collectors.toSet());
        }
    }

    /**
     * The filler spans are two per week on different traces of the same project, so only partition pruning, not the
     * primary key, can keep a read out of those weeks.
     */
    private SeededThread seedThreadWithSpansInManyWeeks() {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var now = Instant.now();
        var farFuture = Instant.parse("2201-08-30T03:18:08Z");
        var threadId = "thread-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var traceId = traceResourceClient.createTrace(factory.manufacturePojo(Trace.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(now.toEpochMilli()))
                .projectName(projectName)
                .threadId(threadId)
                .startTime(now)
                .endTime(now.plusMillis(100))
                .errorInfo(null)
                .feedbackScores(null)
                .usage(null)
                .build(), API_KEY, WORKSPACE_NAME);
        spanResourceClient.batchCreateSpans(List.of(
                newSpan(now, traceId).toBuilder().projectName(projectName)
                        .totalEstimatedCost(new BigDecimal("1.25")).build(),
                newSpan(farFuture, traceId).toBuilder().projectName(projectName)
                        .totalEstimatedCost(new BigDecimal("2.5")).build()),
                API_KEY, WORKSPACE_NAME);
        spanResourceClient.batchCreateSpans(FILLER_MONDAYS.stream()
                .flatMap(monday -> Stream.of(0, 1).map(_ -> newSpan(
                        monday.atTime(12, 0).toInstant(ZoneOffset.UTC), ID_GENERATOR.generateId())
                        .toBuilder().projectName(projectName).build()))
                .toList(), API_KEY, WORKSPACE_NAME);
        traceResourceClient.awaitThreadRows(List.of(threadId), projectId, null, API_KEY, WORKSPACE_NAME);
        var spanWeeks = Stream.concat(FILLER_WEEKS.stream(),
                Stream.of(yyyymmdd(mondayOf(now)), yyyymmdd(mondayOf(farFuture))))
                .collect(Collectors.toSet());
        return new SeededThread(projectId, now, spanWeeks);
    }

    @Test
    void alertTotalCostCountsAFarFutureSpanOfATraceInTheWindow() {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var now = Instant.now();
        // The alert window bounds trace_id only, so the far-future week must come from the span-weeks pre-pass.
        var expected = totalCost(createTraceWithAFarFutureSpan(projectName, now).spans());

        var actual = fireTraceCostAlert(projectId);

        assertThat(new BigDecimal(actual.metricValue())).isEqualByComparingTo(expected);
    }

    /** Each read takes the project name and the trace id, and returns the traces it read. */
    private Stream<Arguments> traceReads() {
        return Stream.of(
                arguments("find_traces_by_project_id", (BiFunction<String, UUID, List<Trace>>) (projectName,
                        _) -> traceResourceClient.getByProjectName(projectName, API_KEY, WORKSPACE_NAME)),
                arguments("find_trace_stream", (BiFunction<String, UUID, List<Trace>>) (projectName,
                        _) -> traceResourceClient.getStreamAndAssertContent(API_KEY, WORKSPACE_NAME,
                                TraceSearchStreamRequest.builder().projectName(projectName).build())),
                arguments("find_traces_by_ids", (BiFunction<String, UUID, List<Trace>>) (_,
                        traceId) -> List.of(traceResourceClient.getById(traceId, WORKSPACE_NAME, API_KEY))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("traceReads")
    void traceAggregatesAFarFutureSpanOfItsTrace(String queryName, BiFunction<String, UUID, List<Trace>> read) {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);
        projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        // The spans reads are keyed by trace id, so the far-future week must come from the span-weeks pre-pass.
        var created = createTraceWithAFarFutureSpan(projectName, Instant.now());
        var expected = SpanAggregates.builder().spanCount(created.spans().size())
                .totalEstimatedCost(totalCost(created.spans())).build();

        var actual = read.apply(projectName, created.trace().id());

        // The whole trace, with the usage its spans add up to, then the span aggregates the trace assertion leaves out.
        var expectedTrace = created.trace().toBuilder().usage(totalUsage(created.spans())).build();
        TraceAssertions.assertTraces(actual, List.of(expectedTrace), USER);
        assertThat(actual)
                .map(trace -> SpanAggregates.builder().spanCount(trace.spanCount())
                        .totalEstimatedCost(trace.totalEstimatedCost()).build())
                .usingRecursiveFieldByFieldElementComparator(RecursiveComparisonConfiguration.builder()
                        .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                        .build())
                .containsExactly(expected);
    }

    /** The span count and total cost reported by a trace read. */
    @Builder(toBuilder = true)
    private record SpanAggregates(int spanCount, BigDecimal totalEstimatedCost) {
    }

    /** A trace and the spans written for it. */
    @Builder(toBuilder = true)
    private record TraceWithSpans(Trace trace, List<Span> spans) {
    }

    /**
     * The alert job is the only reader of the alert total cost, so it is driven through a cost alert: a zero threshold
     * fires on the first run, and the webhook carries the total the job read.
     */
    private MetricsAlertPayload fireTraceCostAlert(UUID projectId) {
        var webhookPath = "/alert-webhook-" + RandomStringUtils.secure().nextAlphanumeric(12);
        wireMock.server().stubFor(post(urlEqualTo(webhookPath)).willReturn(aResponse().withStatus(200)));
        // The webhook sender names the workspace in the payload.
        wireMock.server().stubFor(get(urlPathEqualTo("/workspaces/workspace-name"))
                .withQueryParam("id", equalTo(WORKSPACE_ID))
                .willReturn(aResponse().withStatus(200).withBody(WORKSPACE_NAME)));
        var alert = Alert.builder()
                .name("cost-" + RandomStringUtils.secure().nextAlphanumeric(12))
                .enabled(true)
                .alertType(AlertType.GENERAL)
                .projectId(projectId)
                .webhook(Webhook.builder()
                        .url("http://localhost:%d%s".formatted(wireMock.server().port(), webhookPath))
                        .secretToken(UUID.randomUUID().toString())
                        .build())
                .triggers(List.of(AlertTrigger.builder()
                        .eventType(AlertEventType.TRACE_COST)
                        .triggerConfigs(List.of(AlertTriggerConfig.builder()
                                .type(AlertTriggerConfigType.THRESHOLD_COST)
                                .configValue(Map.of(
                                        AlertTriggerConfig.THRESHOLD_CONFIG_KEY, "0",
                                        AlertTriggerConfig.WINDOW_CONFIG_KEY, "3600"))
                                .build()))
                        .build()))
                .build();
        var alertId = alertResourceClient.createAlert(alert, API_KEY, WORKSPACE_NAME, HttpStatus.SC_CREATED);

        String body;
        try {
            body = Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                    .until(() -> wireMock.server().findAll(postRequestedFor(urlEqualTo(webhookPath))),
                            requests -> !requests.isEmpty())
                    .getFirst().getBodyAsString();
        } finally {
            // The scheduler re-fires an enabled alert on every run, so it must not outlive this test.
            alertResourceClient.deleteAlertBatch(BatchDelete.builder().ids(Set.of(alertId)).build(), API_KEY,
                    WORKSPACE_NAME, HttpStatus.SC_NO_CONTENT);
        }
        @SuppressWarnings("unchecked")
        WebhookEvent<Map<String, Object>> event = JsonUtils.readValue(body, WebhookEvent.class);
        var metadata = (List<?>) event.getPayload().get("metadata");
        return JsonUtils.readValue(JsonUtils.writeValueAsString(metadata.getFirst()), MetricsAlertPayload.class);
    }

    /** A trace at {@code now} with one span beside it and one a bad clock files under a far-future week. */
    private TraceWithSpans createTraceWithAFarFutureSpan(String projectName, Instant now) {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(now.toEpochMilli()))
                .projectName(projectName)
                // ClickHouse keeps microseconds, and the CI JVM clock has nanoseconds.
                .startTime(now.truncatedTo(ChronoUnit.MILLIS))
                .endTime(now.truncatedTo(ChronoUnit.MILLIS).plusMillis(100))
                .feedbackScores(null)
                .usage(null)
                .build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        var spans = Stream.of(now, farFutureInstant())
                .map(idAt -> newSpan(idAt, trace.id()).toBuilder()
                        .projectName(projectName)
                        .totalEstimatedCost(BigDecimal.valueOf(RandomUtils.secure().randomInt(1, 100_000), 2))
                        .build())
                .toList();
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
        return TraceWithSpans.builder().trace(trace).spans(spans).build();
    }

    private static Map<String, Long> totalUsage(List<Span> spans) {
        return spans.stream()
                .flatMap(span -> span.usage().entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().longValue(), Long::sum));
    }

    private static BigDecimal totalCost(List<Span> spans) {
        return spans.stream().map(Span::totalEstimatedCost).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Past every real clock, short of where {@code id_at} saturates. */
    private static Instant farFutureInstant() {
        return LocalDate.of(RandomUtils.secure().randomInt(2150, 2290), 1, 1)
                .plusDays(RandomUtils.secure().randomInt(0, 365))
                .atTime(12, 0)
                .toInstant(ZoneOffset.UTC);
    }

    @Test
    void spanStatsCountSpansInEveryWeekWithoutASearch() {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var windowWeeks = RandomUtils.secure().randomInt(2, 20);
        var fromTime = THIS_MONDAY.minusWeeks(windowWeeks).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        // Two ordinary weeks inside the window and the far-future one a bad clock files spans under.
        var ordinary = Stream.generate(() -> THIS_MONDAY.minusWeeks(RandomUtils.secure().randomInt(0, windowWeeks)))
                .distinct()
                .limit(2)
                .map(monday -> monday.plusDays(2).atTime(12, 0).toInstant(ZoneOffset.UTC));
        var spans = Stream.concat(ordinary, Stream.of(farFutureInstant()))
                .map(idAt -> newSpan(idAt, ID_GENERATOR.generateId()).toBuilder()
                        .projectName(projectName)
                        .duration(null)
                        .totalEstimatedCost(null)
                        .build())
                .toList();
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
        var expected = StatsUtils.getProjectSpanStatItems(spans);

        var actual = spanResourceClient.getSpansStats(projectName, null, null, API_KEY, WORKSPACE_NAME,
                Map.of("from_time", fromTime));

        TraceAssertions.assertStats(actual.stats(), expected);
    }

    private static KpiCardResponse.KpiMetric kpi(KpiCardResponse.KpiMetricType type, Double current,
            Double previous) {
        return KpiCardResponse.KpiMetric.builder().type(type).currentValue(current).previousValue(previous).build();
    }

    /** The searched spans and the responses. */
    @Builder(toBuilder = true)
    private record SpanSearch(String token, List<Span> expected, Span.SpanPage page, ProjectStats stats) {
    }

    private SpanSearch spanSearch() {
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
        return SpanSearch.builder().token(token).expected(expected).page(page).stats(stats).build();
    }

    @Test
    void spanSearchBoundedToProjectWeeksReturnsEveryMatch() {
        var search = spanSearch();

        assertThat(search.page().total()).isEqualTo(search.expected().size());
        SpanAssertions.assertSpan(search.page().content(), search.expected(), USER);
        TraceAssertions.assertStats(search.stats().stats(), StatsUtils.getProjectSpanStatItems(search.expected()));
    }

    /** One span search shared by the statement cases, each checking a different statement of it. */
    private Stream<Arguments> spanSearchStatements() {
        var search = spanSearch();
        return Stream.of("count_spans_by_project_id", "get_span_stats", "get_span_stats_feedback_scores")
                .map(queryName -> arguments(queryName, search));
    }

    @ParameterizedTest(name = "{0} prunes the spans partitions")
    @MethodSource("spanSearchStatements")
    void spanSearchStatementPrunesPartitions(String queryName, SpanSearch search) {
        assertPlanPrunesSpanPartitions(queryName, search.token());
    }

    @Test
    void spanFindSearchPrunesPartitions() {
        var search = spanSearch();

        assertSubqueryPrunesSpanPartitions("find_spans_by_project_id", search.token());
    }

    /**
     * Every {@code spans} read in the plan of the statement this search sent selects fewer parts by partition key than
     * the table holds. The search's only partition-key predicate is the week hint, so this is the hint pruning.
     */
    private void assertPlanPrunesSpanPartitions(String queryName, String token) {
        var plan = JsonUtils.getJsonNodeFromString(String.join("\n", template.stream(connection -> Flux.from(
                connection.createStatement("EXPLAIN indexes = 1, json = 1 " + searchStatement(queryName, token)
                        .query())
                        .execute())
                .flatMap(result -> result.map((row, _) -> row.get(0, String.class)))).collectList().block()));
        var partitionSelections = plan.findParents("Node Type").stream()
                .filter(node -> "ReadFromMergeTree".equals(node.path("Node Type").asText()))
                .filter(node -> "%s.spans".formatted(DATABASE_NAME).equals(node.path("Description").asText()))
                .flatMap(node -> StreamSupport.stream(node.path("Indexes").spliterator(), false))
                .filter(index -> "Partition".equals(index.path("Type").asText()))
                .toList();
        assertThat(partitionSelections)
                .as("every spans read of %s selects by partition key", queryName)
                .isNotEmpty()
                .allSatisfy(index -> assertThat(index.path("Selected Parts").asInt())
                        .isLessThan(index.path("Initial Parts").asInt()));
    }

    /**
     * The find statement runs its search in a scalar subquery, whose read the plan does not show, so its partition
     * selection is read from the server log ClickHouse writes for every read of the statement.
     */
    private void assertSubqueryPrunesSpanPartitions(String queryName, String token) {
        var queryId = searchStatement(queryName, token).queryId();
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(partitionKeySelections(queryId))
                        .as("%s reads spans with a partition-key selection", queryName)
                        .anySatisfy(selection -> assertThat(selection.getLeft()).isLessThan(selection.getRight())));
    }

    /** Each read's "Selected N/M parts by partition key", as (N, M). */
    private List<Pair<Integer, Integer>> partitionKeySelections(String queryId) {
        return template.stream(connection -> Flux.from(connection.createStatement(PARTITION_KEY_SELECTIONS)
                .bind("query_id", queryId)
                .bind("logger", "%s.spans %%".formatted(DATABASE_NAME))
                .execute())
                .flatMap(result -> result.map((row, _) -> row.get(0, String.class))))
                .map(PARTITION_KEY_SELECTION::matcher)
                .filter(Matcher::find)
                .map(matcher -> Pair.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))))
                .collectList()
                .block();
    }

    private record SearchStatement(String queryId, String query) {
    }

    /** Polled: a statement's query_log row is written asynchronously, flushed every 200 ms here. */
    private SearchStatement searchStatement(String queryName, String token) {
        return Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> template.nonTransaction(connection -> Mono.from(connection
                        .createStatement(SEARCH_STATEMENT)
                        .bind("query_name", queryName)
                        .bind("token", token)
                        .execute())
                        .flatMap(result -> Mono.from(result.map((row, _) -> new SearchStatement(
                                row.get(0, String.class), row.get(1, String.class))))))
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
