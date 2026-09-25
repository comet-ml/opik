package com.comet.opik.infrastructure;

import com.comet.opik.api.Comment;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatchUpdate;
import com.comet.opik.api.SpanUpdate;
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
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;
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
    private TransactionTemplateAsync template;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        this.datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
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
        // Span creation itself runs the partial lookup and the insert.
        Function<Span, UUID> created = Span::id;
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
                arguments("insert_span", created),
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
        var id = trigger.apply(createSpan(Instant.now(), ID_GENERATOR.generateId()));

        assertThat(spansPartitionsRead(queryName, id)).isSubsetOf(THIS_WEEK);
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
                // Not a root span and no usage: the successor stores an empty parent as NUL padding and usage as
                // Int64, neither of which the read mapping accepts yet. Both are cutover concerns outside these bounds.
                .parentSpanId(ID_GENERATOR.generateId())
                .usage(null)
                .startTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .endTime(null)
                .feedbackScores(null)
                .build();
    }

    /** Polled: a query's {@code query_log} row is written asynchronously, flushed every 200 ms here. */
    private Set<String> spansPartitionsRead(String queryName, UUID spanId) {
        var partitions = Awaitility.await()
                .alias("query_log holds a %s statement mentioning id %s".formatted(queryName, spanId))
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> template.nonTransaction(connection -> Mono.from(connection
                        .createStatement(SPANS_PARTITIONS_READ)
                        .bind("prefix", PARTITION_PREFIX)
                        .bind("op", queryName)
                        .bind("span_id", spanId.toString())
                        .execute())
                        .flatMap(result -> Mono.from(result.map((row, _) -> row.get(0, String.class)))))
                        .block(), Objects::nonNull);
        return Arrays.stream(partitions.split(","))
                .filter(partition -> !partition.isEmpty())
                .map(partition -> partition.substring(PARTITION_PREFIX.length()))
                .collect(Collectors.toSet());
    }

    private static String yyyymmdd(LocalDate monday) {
        return monday.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
