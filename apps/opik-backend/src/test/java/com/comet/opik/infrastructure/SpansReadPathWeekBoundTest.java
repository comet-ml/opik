package com.comet.opik.infrastructure;

import com.comet.opik.api.Comment;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentStatus;
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
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.domain.ExperimentItemService;
import com.comet.opik.domain.ExperimentTraceRef;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Statement;
import org.apache.commons.lang3.RandomStringUtils;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The six span-id reads OPIK-8361 gave a week bound: {@code get_spans_by_ids} and the
 * {@code get_target_project_ids_for_spans} it runs first, {@code get_partial_span_by_id}, {@code get_only_span_by_id},
 * {@code get_project_id_from_span}, {@code get_experiment_refs_by_span_ids}, and the reads inside the write and
 * validation paths ({@code insert_span}, {@code update_span}, {@code partial_insert_span}, {@code bulk_update_spans},
 * {@code get_span_workspace}) — the spans counterpart of {@link TracesReadPathWeekBoundTest}.
 *
 * <p>Driven through the endpoints that reach them ({@code POST /spans}, {@code GET} / {@code PATCH /spans/{id}},
 * {@code POST /spans/{id}/comments}, {@code PATCH /spans/batch}, {@code POST /datasets/items}); the experiment-refs read is called directly, its only caller being an event
 * listener. Every span is ingested with the timestamp-window check off (the production default), so far-future ids
 * are the ones a client can still send today.
 *
 * <p>Three claims per site: rows are unchanged, including for far-future ids on this estate's legacy 32-bit
 * {@code id_at} (where the row sits under the wrapped week); the bound is present for a derivable id and absent at or
 * past the 2300 ceiling; and the statement ClickHouse actually received, pointed at {@code spans_local_v2}, prunes on
 * the {@code Partition} entry of {@code EXPLAIN indexes = 1} — so a site whose bound stops matching the partition key
 * fails here, not in production.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpansReadPathWeekBoundTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private static final String GET_SPANS_BY_IDS = "get_spans_by_ids";
    private static final String GET_TARGET_PROJECT_IDS = "get_target_project_ids_for_spans";
    private static final String GET_PARTIAL_SPAN_BY_ID = "get_partial_span_by_id";
    private static final String GET_ONLY_SPAN_BY_ID = "get_only_span_by_id";
    private static final String GET_PROJECT_ID_FROM_SPAN = "get_project_id_from_span";
    private static final String GET_EXPERIMENT_REFS_BY_SPAN_IDS = "get_experiment_refs_by_span_ids";
    private static final String INSERT_SPAN = "insert_span";
    private static final String UPDATE_SPAN = "update_span";
    private static final String PARTIAL_INSERT_SPAN = "partial_insert_span";
    private static final String BULK_UPDATE_SPANS = "bulk_update_spans";
    private static final String GET_SPAN_WORKSPACE = "get_span_workspace";

    /** A second workspace, so a cross-workspace span reference has something to be rejected by. */
    private static final String OTHER_API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String OTHER_WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String OTHER_WORKSPACE_ID = UUID.randomUUID().toString();

    /** Past 2106, the only era whose two {@code id_at} representations differ. */
    private static final Instant FAR_FUTURE_ID_AT = Instant.parse("2200-01-01T00:00:00Z");

    /**
     * {@link #FAR_FUTURE_ID_AT}'s week as the legacy 32-bit {@code id_at} stores it (wrapped mod 2^32 seconds) and as
     * {@code spans_local_v2} does. Computed once in ClickHouse 26.3 via
     * {@code toYYYYMMDD(toDate32(d) - toIntervalDay(toDayOfWeek(d, 1)))} over the honest and the wrapped instant.
     */
    private static final String FAR_FUTURE_LEGACY_WEEK = "20631119";
    private static final String FAR_FUTURE_HONEST_WEEK = "21991230";

    /** Where {@code id_at} saturates on the successor, so the bound cannot be derived and must be absent. */
    private static final Instant PAST_CEILING_ID_AT = LocalDate.of(2300, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

    /** None of these queries name {@code id_at} anywhere else, so its presence is exactly "the bound rendered". */
    private static final String WEEK_BOUND_MARKER = "id_at";

    /** Historical weeks seeded into {@code spans_local_v2}, so a pruned plan has partitions to exclude. */
    private static final LocalDate FILLER_ANCHOR_MONDAY = LocalDate.of(2025, 3, 3);

    private static final String LAST_STATEMENT_FOR = """
            SELECT query
            FROM system.query_log
            WHERE log_comment LIKE concat(:op, ':%')
            AND type = 'QueryFinish'
            AND query LIKE concat('%', :span_id, '%')
            ORDER BY event_time_microseconds DESC
            LIMIT 1
            """;

    /** The experiment-refs spans read is an {@code IN} subquery, which EXPLAIN evaluates as a set, not plans. */
    private static final Pattern SPANS_SUBQUERY = Pattern.compile(
            "(?s)IN \\(\\s*(SELECT DISTINCT trace_id FROM spans_local_v2.*?)\\)\\s*AND ea\\.status");

    /** The {@code INSERT INTO spans (...)} head of a write site, leaving its {@code SELECT}. */
    private static final Pattern INSERT_PREFIX = Pattern
            .compile("(?s)^\\s*INSERT INTO \\w+\\s*\\(.*?\\)\\s*(?=SELECT)");

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
                clickHouseContainer, ClickHouseContainerUtils.DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        // The production default; it is what lets a far-future id reach the table via ingestion.
                        .customConfigs(List.of(new CustomConfig("uuidValidation.enabled", "false")))
                        .build());
    }

    private SpanResourceClient spanResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private TransactionTemplateAsync template;
    private ExperimentItemService experimentItemService;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template,
            ExperimentItemService experimentItemService) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        mockTargetWorkspace(wireMock.server(), OTHER_API_KEY, OTHER_WORKSPACE_NAME, OTHER_WORKSPACE_ID, USER);
        this.datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        this.experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        this.template = template;
        this.experimentItemService = experimentItemService;
        seedSpansLocalV2Filler();
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    /** Each trigger reaches its site for a span already ingested, and returns the id its statement mentions. */
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

        return Stream.of(
                // A get-by-id runs the target-projects read first, so one trigger covers both query names.
                arguments(GET_SPANS_BY_IDS, getById),
                arguments(GET_TARGET_PROJECT_IDS, getById),
                arguments(GET_PARTIAL_SPAN_BY_ID, created),
                arguments(GET_ONLY_SPAN_BY_ID, update),
                arguments(GET_PROJECT_ID_FROM_SPAN, (Function<Span, UUID>) span -> {
                    try (var response = spanResourceClient.callAddSpanComment(span.id(),
                            Comment.builder().text("week-bound").build(), API_KEY, WORKSPACE_NAME)) {
                        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
                    }
                    return span.id();
                }),
                arguments(GET_EXPERIMENT_REFS_BY_SPAN_IDS, (Function<Span, UUID>) span -> {
                    experimentRefsBySpanIds(Set.of(span.id()));
                    return span.id();
                }),
                arguments(INSERT_SPAN, created),
                arguments(UPDATE_SPAN, update),
                // A PATCH of an id nobody created yet, minted in the same week as the given span.
                arguments(PARTIAL_INSERT_SPAN, (Function<Span, UUID>) span -> {
                    var id = ID_GENERATOR.getTimeOrderedEpoch(span.id().getMostSignificantBits() >>> 16);
                    updateTags(span.toBuilder().id(id).build());
                    return id;
                }),
                arguments(BULK_UPDATE_SPANS, (Function<Span, UUID>) span -> {
                    batchUpdateTags(span, Set.of(span.id()));
                    return span.id();
                }),
                arguments(GET_SPAN_WORKSPACE, (Function<Span, UUID>) span -> {
                    datasetResourceClient.createDatasetItems(datasetItemReferencing(span), WORKSPACE_NAME,
                            API_KEY);
                    return span.id();
                }));
    }

    /** A site that bound {@code :id_weeks} unconditionally fails the underivable half; one that lost it, the other. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sites")
    void everySiteBoundsWhatItCanDeriveAndFallsBackOtherwise(String queryName, Function<Span, UUID> trigger) {
        var derivable = trigger.apply(createSpan(Instant.now(), ID_GENERATOR.generateId()));
        var underivable = trigger.apply(createSpan(PAST_CEILING_ID_AT, ID_GENERATOR.generateId()));

        assertThat(statementFor(queryName, derivable)).contains(WEEK_BOUND_MARKER);
        assertThat(statementFor(queryName, underivable)).doesNotContain(WEEK_BOUND_MARKER);
    }

    /**
     * The statement each site actually sent, retargeted at {@code spans_local_v2}, prunes on the {@code Partition}
     * entry: the bound only prunes if it is the partition key's own expression, which no marker check can see.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sites")
    void everySiteStatementPrunesPartitionsOnTheWeeklyPartitionedTable(String queryName,
            Function<Span, UUID> trigger) {
        var id = trigger.apply(createSpan(Instant.now(), ID_GENERATOR.generateId()));

        // The write sites are INSERT ... SELECT; EXPLAIN the SELECT, which is where the spans read is.
        var statement = INSERT_PREFIX.matcher(statementFor(queryName, id)).replaceFirst("")
                .replaceAll("\\bFROM spans\\b(?!_)", "FROM spans_local_v2")
                .replaceAll(";\\s*$", "");
        var subquery = SPANS_SUBQUERY.matcher(statement);
        var partition = partitionEntryForSpansLocalV2(subquery.find() ? subquery.group(1) : statement);

        assertThat(partition.path("Selected Parts").asInt())
                .as("Partition entry for %s: %s", queryName, partition)
                .isLessThan(partition.path("Initial Parts").asInt());
    }

    @Test
    void getSpanByIdResolvesFarFutureAndPastCeilingSpans() {
        // Legacy id_at wraps a far-future id, so a bound naming only the honest week would make this a 404.
        var farFuture = createSpan(FAR_FUTURE_ID_AT, ID_GENERATOR.generateId());
        var pastCeiling = createSpan(PAST_CEILING_ID_AT, ID_GENERATOR.generateId());

        assertThat(spanResourceClient.getById(farFuture.id(), WORKSPACE_NAME, API_KEY).id()).isEqualTo(farFuture.id());
        assertThat(spanResourceClient.getById(pastCeiling.id(), WORKSPACE_NAME, API_KEY).id())
                .isEqualTo(pastCeiling.id());
        // Both weeks written out, not derived: the wrapped one is where this estate holds the row.
        assertThat(statementFor(GET_SPANS_BY_IDS, farFuture.id()))
                .contains(FAR_FUTURE_LEGACY_WEEK)
                .contains(FAR_FUTURE_HONEST_WEEK);
    }

    @Test
    void createAfterPatchKeepsThePatchedValuesOfAFarFutureSpan() {
        // PATCH first takes the partial-insert path; the later POST merges over it through INSERT's old_span read,
        // which prefers the stored name, so a missed read would surface the POSTed name instead.
        var span = newSpan(FAR_FUTURE_ID_AT, ID_GENERATOR.generateId());
        spanResourceClient.updateSpan(span.id(), SpanUpdate.builder()
                .projectName(span.projectName())
                .traceId(span.traceId())
                .name("patched-name")
                .tags(Set.of("week-bound"))
                .build(), API_KEY, WORKSPACE_NAME);
        assertThat(spanResourceClient.getById(span.id(), WORKSPACE_NAME, API_KEY).tags())
                .containsExactly("week-bound");

        spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);

        var actual = spanResourceClient.getById(span.id(), WORKSPACE_NAME, API_KEY);
        assertThat(actual.name()).isEqualTo("patched-name");
        assertThat(actual.startTime()).isEqualTo(span.startTime());
    }

    private Stream<Arguments> farFutureAndPastCeilingIdAts() {
        return Stream.of(
                arguments(Named.of("far future", FAR_FUTURE_ID_AT)),
                arguments(Named.of("past ceiling", PAST_CEILING_ID_AT)));
    }

    private Stream<Arguments> recentFarFutureAndPastCeilingIdAts() {
        return Stream.concat(Stream.of(arguments(Named.of("recent", Instant.now()))), farFutureAndPastCeilingIdAts());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("farFutureAndPastCeilingIdAts")
    void batchUpdateReachesTheSpan(Instant idAt) {
        // BULK_UPDATE rewrites the rows it reads, so a missed read leaves the tags unchanged.
        var span = createSpan(idAt, ID_GENERATOR.generateId());

        batchUpdateTags(span, Set.of(span.id()));

        var actual = spanResourceClient.getById(span.id(), WORKSPACE_NAME, API_KEY);
        assertThat(actual.tags()).as("tags of %s (id_at %s)", span.id(), idAt).containsExactly("week-bound");
        assertThat(actual.name()).as("name of %s (id_at %s)", span.id(), idAt).isEqualTo(span.name());
    }

    @Test
    void datasetItemCreationRejectsAFarFutureSpanFromAnotherWorkspace() {
        // The caller reduces with allMatch, which is true over an empty result, so a missed read would accept this.
        var farFuture = createSpan(FAR_FUTURE_ID_AT, ID_GENERATOR.generateId());

        try (var response = datasetResourceClient.callCreateDatasetItems(
                datasetItemReferencing(farFuture), OTHER_WORKSPACE_NAME, OTHER_API_KEY)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
        }
    }

    @Test
    void updateKeepsAFarFutureSpanIntact() {
        var farFuture = createSpan(FAR_FUTURE_ID_AT, ID_GENERATOR.generateId());

        updateTags(farFuture);

        var actual = spanResourceClient.getById(farFuture.id(), WORKSPACE_NAME, API_KEY);
        assertThat(actual.tags()).containsExactly("week-bound");
        assertThat(actual.name()).isEqualTo(farFuture.name());
    }

    @Test
    void commentOnAFarFutureSpanResolvesItsProject() {
        // A lost project lookup is a 404 here.
        var farFuture = createSpan(FAR_FUTURE_ID_AT, ID_GENERATOR.generateId());

        try (var response = spanResourceClient.callAddSpanComment(farFuture.id(),
                Comment.builder().text("week-bound").build(), API_KEY, WORKSPACE_NAME)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recentFarFutureAndPastCeilingIdAts")
    void experimentRefsResolveTheSpan(Instant idAt) {
        var traceId = ID_GENERATOR.generateId();
        var experiment = experimentResourceClient.createPartialExperiment()
                .status(ExperimentStatus.COMPLETED)
                .build();
        experimentResourceClient.create(experiment, API_KEY, WORKSPACE_NAME);
        experimentResourceClient.createExperimentItem(Set.of(factory.manufacturePojo(ExperimentItem.class)
                .toBuilder()
                .experimentId(experiment.id())
                .traceId(traceId)
                .build()), API_KEY, WORKSPACE_NAME);
        var span = createSpan(idAt, traceId);

        // One id per call: a mixed batch would drop the bound for all of them and prove nothing about the set.
        assertThat(experimentRefsBySpanIds(Set.of(span.id())))
                .extracting(ExperimentTraceRef::experimentId)
                .as("experiment refs for span %s (id_at %s)", span.id(), idAt)
                .containsExactly(experiment.id());
    }

    @Test
    void oneUnderivableIdDropsTheBoundForTheWholeBatch() {
        var traceId = ID_GENERATOR.generateId();
        var derivable = createSpan(Instant.now(), traceId);
        var underivable = createSpan(PAST_CEILING_ID_AT, traceId);

        experimentRefsBySpanIds(Set.of(derivable.id(), underivable.id()));

        assertThat(statementFor(GET_EXPERIMENT_REFS_BY_SPAN_IDS, derivable.id())).doesNotContain(WEEK_BOUND_MARKER);
    }

    private List<ExperimentTraceRef> experimentRefsBySpanIds(Set<UUID> spanIds) {
        return experimentItemService.getExperimentRefsBySpanIds(spanIds, Set.of(ExperimentStatus.COMPLETED), null)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER))
                .collectList()
                .block();
    }

    private void batchUpdateTags(Span span, Set<UUID> ids) {
        spanResourceClient.batchUpdateSpans(SpanBatchUpdate.builder()
                .ids(ids)
                .update(SpanUpdate.builder().traceId(span.traceId()).tags(Set.of("week-bound")).build())
                .build(), API_KEY, WORKSPACE_NAME);
    }

    /** A one-item batch into a fresh dataset, referencing {@code span} - the shape that reaches the check. */
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

    private void updateTags(Span span) {
        spanResourceClient.updateSpan(span.id(), SpanUpdate.builder()
                .projectName(span.projectName())
                .traceId(span.traceId())
                .tags(Set.of("week-bound"))
                .build(), API_KEY, WORKSPACE_NAME);
    }

    /** A span through the real ingestion path whose {@code id} carries {@code idAt}; {@code startTime} stays today. */
    private Span createSpan(Instant idAt, UUID traceId) {
        var span = newSpan(idAt, traceId);
        spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);
        return span;
    }

    private Span newSpan(Instant idAt, UUID traceId) {
        return factory.manufacturePojo(Span.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(idAt.toEpochMilli()))
                .projectName("project-" + RandomStringUtils.secure().nextAlphanumeric(16))
                .traceId(traceId)
                .parentSpanId(null)
                .startTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .endTime(null)
                .feedbackScores(null)
                .build();
    }

    /** Three historical weeks in one INSERT, so {@code spans_local_v2} holds parts a bounded read can exclude. */
    private void seedSpansLocalV2Filler() {
        var ids = Stream.of(0, 1, 2)
                .map(week -> ID_GENERATOR.generateId(
                        FILLER_ANCHOR_MONDAY.plusWeeks(week).atTime(12, 0).toInstant(ZoneOffset.UTC)))
                .toList();
        var sql = TemplateUtils.getBatchSql("""
                INSERT INTO spans_local_v2 (id, workspace_id, project_id, trace_id)
                FORMAT Values
                    <items:{item | (:id<item.index>, :workspace_id, :project_id, :trace_id)<if(item.hasNext)>,<endif>}>
                ;
                """, ids.size()).render();
        template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql)
                    .bind("workspace_id", WORKSPACE_ID)
                    .bind("project_id", ID_GENERATOR.generateId())
                    .bind("trace_id", ID_GENERATOR.generateId());
            for (int index = 0; index < ids.size(); index++) {
                statement.bind("id" + index, ids.get(index));
            }
            return Mono.from(statement.execute());
        }).block();
    }

    /** The {@code Partition} index entry of the plan's one {@code spans_local_v2} read; fails if there is none. */
    private JsonNode partitionEntryForSpansLocalV2(String selectSql) {
        var explain = String.join("\n", template.stream(connection -> Flux
                .from(connection.createStatement("EXPLAIN indexes = 1, json = 1 %s".formatted(selectSql)).execute())
                .flatMap(result -> result.map((row, _) -> row.get("explain", String.class))))
                .collectList()
                .block());

        var reads = JsonUtils.getJsonNodeFromString(explain).findParents("Indexes").stream()
                .filter(node -> node.path("Description").asText().contains("spans_local_v2"))
                .toList();
        assertThat(reads).as("spans_local_v2 reads in EXPLAIN output:%n%s", explain).hasSize(1);

        var partitions = reads.getFirst().path("Indexes").valueStream()
                .filter(entry -> "Partition".equals(entry.path("Type").asText()))
                .toList();
        assertThat(partitions).as("Partition index entries in EXPLAIN output:%n%s", explain).hasSize(1);
        return partitions.getFirst();
    }

    /** Polled: a query's {@code query_log} row is written asynchronously, flushed every 200 ms here. */
    private String statementFor(String queryName, UUID spanId) {
        return Awaitility.await()
                .alias("query_log holds a %s statement mentioning id %s".formatted(queryName, spanId))
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> queryOneString(statement -> statement
                        .bind("op", queryName)
                        .bind("span_id", spanId.toString())), Objects::nonNull);
    }

    private String queryOneString(Consumer<Statement> binder) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(LAST_STATEMENT_FOR);
            binder.accept(statement);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, _) -> row.get(0, String.class))));
        }).block();
    }
}
