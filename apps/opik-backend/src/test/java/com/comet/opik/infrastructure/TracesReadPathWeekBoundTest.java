package com.comet.opik.infrastructure;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatchUpdate;
import com.comet.opik.api.TraceUpdate;
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
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.WeeklyPartitions;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Statement;
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
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The five trace-id-list reads of {@code TraceDAO} that OPIK-8332 gave a week bound: {@code find_traces_by_ids} and
 * the {@code get_target_project_ids_for_traces} it runs first, {@code get_project_ids_by_trace_ids},
 * {@code get_trace_workspace}, and {@code get_start_times_by_trace_ids}.
 *
 * <p>Driven through the endpoints that reach them — {@code GET /v1/private/traces/{id}},
 * {@code PATCH /v1/private/traces/batch} and {@code POST /v1/private/datasets/items} — so what is asserted is what a
 * client sees. {@code get_start_times_by_trace_ids} is the one exception and is called directly: its only caller is
 * an ingestion-side listener that no request reaches.
 *
 * <p><b>Every trace here is created through the ingestion endpoint, far-future ids included.</b> Ingestion rejects a
 * non-v7 id unconditionally but checks the timestamp window only under the {@code uuidValidation} kill-switch, which
 * is off by default — so these are the ids a client can still send today, and the suite needs no raw {@code INSERT}
 * to produce them.
 *
 * <p><b>Rows first, because the bound's whole contract is that it changes none.</b> It is a strict consequence of
 * {@code id IN :ids}, and the way it can fail is silent: fewer rows, no error, which for a far-future trace means a
 * 404 for a trace the client just created.
 *
 * <p><b>Then that the statement still carries the bound</b>, which rows cannot see: a bound that stopped being
 * emitted leaves the query correct and merely opening every partition again, and on the project lookup a lost id has
 * no row-level symptom at all. Presence-or-absence only — the values are {@link WeeklyPartitions}' contract, pinned
 * by its unit test, and that the predicate prunes is pinned by the {@code EXPLAIN indexes = 1} gate in
 * {@link TracesLocalV2PartitioningTest}; asserting them here again would only couple this suite to the SQL text.
 *
 * <p>Runs on whatever estate the migrations produce, with no topology of its own. Today that is the legacy
 * {@code traces}, whose 32-bit {@code id_at} wraps a far-future timestamp — the harder direction, since a set
 * derived only from the successor's {@code DateTime64} would match zero rows for such an id. It is the row resolving
 * here, not a pinned week value, that shows the right week is in the set.
 *
 * <p>A dedicated, non-reused ClickHouse carrying {@code clickhouse-fast-log-flush.xml}, so the statement reads poll
 * {@code system.query_log} rather than forcing a server-wide {@code SYSTEM FLUSH LOGS} that races the rows it is
 * meant to reveal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesReadPathWeekBoundTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    /**
     * A second workspace, for the one assertion that can tell a resolved row from a lost one on
     * {@code get_trace_workspace}: a reference from another workspace must be rejected, and only a row that came
     * back can reject it.
     */
    private static final String OTHER_API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String OTHER_WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String OTHER_WORKSPACE_ID = UUID.randomUUID().toString();

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    /** The {@code log_comment} query names the DAO stamps, by which each statement is read back. */
    private static final String FIND_TRACES_BY_IDS = "find_traces_by_ids";
    private static final String GET_TARGET_PROJECT_IDS = "get_target_project_ids_for_traces";
    private static final String GET_PROJECT_IDS = "get_project_ids_by_trace_ids";
    private static final String GET_TRACE_WORKSPACE = "get_trace_workspace";
    private static final String GET_START_TIMES = "get_start_times_by_trace_ids";

    /**
     * The {@code id_at} a far-future id carries — the shape a broken client clock mints (litellm
     * <a href="https://github.com/BerriAI/litellm/issues/31294">BerriAI/litellm#31294</a>) — past 2106 and so the only
     * era whose two {@code id_at} representations differ.
     */
    private static final Instant FAR_FUTURE_ID_AT = Instant.parse("2200-01-01T00:00:00Z");

    /**
     * The first instant {@code id_at} cannot represent on the partitioned successor, where it saturates rather than
     * storing the real week — the one underivable cause that occurs in real data. A non-v7 id would also disable the
     * bound, but ingestion rejects those unconditionally, so it is not the case exercised here.
     */
    private static final Instant PAST_CEILING_ID_AT = LocalDate.of(2300, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

    /**
     * The only marker the statement checks look for. These five queries name {@code id_at} nowhere else, so its
     * presence is exactly "the week bound rendered" and its absence exactly "this batch fell back to the unbounded
     * form" — without pinning how the predicate is spelled.
     */
    private static final String WEEK_BOUND_MARKER = "id_at";

    /** What {@link #batchUpdateTags} writes, so the input and the expectation are one value. */
    private static final Set<String> BATCH_UPDATE_TAGS = Set.of("week-bound");

    /**
     * The statement ClickHouse received for one query name, narrowed to this test's own by a trace id that appears in
     * its text. {@code log_comment} alone is not enough: every test here runs the same query names under one
     * workspace, so only an id minted by this test identifies its statement.
     */
    private static final String LAST_STATEMENT_FOR = """
            SELECT query
            FROM system.query_log
            WHERE log_comment LIKE concat(:op, ':%')
            AND type = 'QueryFinish'
            AND query LIKE concat('%', :trace_id, '%')
            ORDER BY event_time_microseconds DESC
            LIMIT 1
            """;

    /** See the class javadoc: opts this container into a 200 ms {@code system.query_log} flush interval. */
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
                        // The production default, which config-test.yml turns on so other suites can cover the
                        // validator itself. Off is the state that produced the rows this suite is about: it is what
                        // lets a client's far-future id reach the table, and so what lets these cases be created by
                        // the ingestion endpoint rather than by an INSERT that bypasses it. Nothing else here
                        // depends on it - the week bound is emitted regardless.
                        .customConfigs(List.of(new CustomConfig("uuidValidation.enabled", "false")))
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private TransactionTemplateAsync template;
    private TraceDAO traceDAO;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template, TraceDAO traceDAO) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        mockTargetWorkspace(wireMock.server(), OTHER_API_KEY, OTHER_WORKSPACE_NAME, OTHER_WORKSPACE_ID, USER);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        this.template = template;
        this.traceDAO = traceDAO;
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    private Stream<Arguments> everySiteBoundsWhatItCanDeriveAndFallsBackOtherwise() {
        // Each trigger is whatever reaches that site with a single trace id. Reading the fields at call time rather
        // than capturing them: @MethodSource runs before @BeforeAll has wired the clients in.
        Consumer<UUID> getById = id -> traceResourceClient.getById(id, WORKSPACE_NAME, API_KEY);

        return Stream.of(
                // A get-by-id runs the target-projects read first and feeds it into the main one, so one trigger
                // covers the two query names it emits.
                arguments(FIND_TRACES_BY_IDS, getById),
                arguments(GET_TARGET_PROJECT_IDS, getById),
                arguments(GET_PROJECT_IDS, (Consumer<UUID>) id -> batchUpdateTags(Set.of(id))),
                arguments(GET_TRACE_WORKSPACE, (Consumer<UUID>) id -> datasetResourceClient
                        .createDatasetItems(datasetItemReferencing(id), WORKSPACE_NAME, API_KEY)),
                arguments(GET_START_TIMES,
                        (Consumer<UUID>) id -> traceDAO.getStartTimesByTraceIds(Set.of(id), WORKSPACE_ID).block()));
    }

    /**
     * The {@code <if(id_weeks)>} branch at every site, in both directions: a derivable id set renders the bound, an
     * underivable one leaves it out and the query reverts to the form it had before this work.
     * <p>
     * Parameterised over the sites rather than written out per site because the claim is identical for all of them
     * and only the trigger differs — and because the fallback half is the direction that would otherwise go
     * untested on the sites whose lost rows have no visible symptom. A site that bound {@code :id_weeks}
     * unconditionally would fail here on the underivable case, with an unbound parameter; one that lost its slot
     * would fail on the derivable case.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void everySiteBoundsWhatItCanDeriveAndFallsBackOtherwise(String queryName, Consumer<UUID> trigger) {
        var derivable = createTrace(Instant.now());
        var underivable = createTrace(PAST_CEILING_ID_AT);

        trigger.accept(derivable.id());
        trigger.accept(underivable.id());

        assertThat(statementFor(queryName, derivable.id())).contains(WEEK_BOUND_MARKER);
        assertThat(statementFor(queryName, underivable.id())).doesNotContain(WEEK_BOUND_MARKER);
    }

    @Test
    void oneUnderivableIdDropsTheBoundForTheWholeBatch() {
        // All-or-nothing: a partially derived set is a set some rows are not in, so one id past the ceiling has to
        // disable the bound for every id in the batch. Every other case here sends a homogeneous batch and so cannot
        // see it. Both rows must still resolve, which is the half that says the fallback is correct and not merely
        // unbounded.
        var derivable = createTrace(Instant.now());
        var underivable = createTrace(PAST_CEILING_ID_AT);

        batchUpdateTags(Set.of(derivable.id(), underivable.id()));

        assertThat(statementFor(GET_PROJECT_IDS, derivable.id())).doesNotContain(WEEK_BOUND_MARKER);
        assertThat(traceResourceClient.getById(derivable.id(), WORKSPACE_NAME, API_KEY).tags())
                .isEqualTo(BATCH_UPDATE_TAGS);
        assertThat(traceResourceClient.getById(underivable.id(), WORKSPACE_NAME, API_KEY).tags())
                .isEqualTo(BATCH_UPDATE_TAGS);
    }

    @Test
    void getTraceByIdResolvesAFarFutureTrace() {
        // The headline risk, and the only era where the bound can be wrong rather than absent. On this schema id_at
        // is a 32-bit DateTime, so the row is filed under the WRAPPED week: a bound carrying only the honest one
        // would match nothing and this would be a 404 for a trace the client just created.
        var expectedTrace = createTrace(FAR_FUTURE_ID_AT);

        var actualTrace = traceResourceClient.getById(expectedTrace.id(), WORKSPACE_NAME, API_KEY);

        assertThat(actualTrace.id()).isEqualTo(expectedTrace.id());
    }

    @Test
    void datasetItemCreationRejectsAFarFutureTraceFromAnotherWorkspace() {
        // The same wrong-week risk where it is worse than a lost row: the caller reduces with allMatch, which over an
        // EMPTY result is true, so a bound that failed to return the row reads as "no workspace mismatch" and the
        // reference is ACCEPTED. A same-workspace reference is accepted either way and so cannot see that; only a
        // cross-workspace one, which must be rejected, can.
        var farFuture = createTrace(FAR_FUTURE_ID_AT);

        try (var actualResponse = datasetResourceClient.callCreateDatasetItems(
                datasetItemReferencing(farFuture.id()), OTHER_WORKSPACE_NAME, OTHER_API_KEY)) {
            assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
        }
    }

    @Test
    void startTimeLookupResolvesAFarFutureTrace() {
        // The same risk on the one site no request reaches, so it is called directly - the same exception the sibling
        // mutation suites take, and for the same reason. Its rows are assertable, so they are asserted.
        var recent = createTrace(Instant.now());
        var farFuture = createTrace(FAR_FUTURE_ID_AT);
        var expectedIds = Set.of(recent.id(), farFuture.id());

        var actualStartTimes = traceDAO.getStartTimesByTraceIds(expectedIds, WORKSPACE_ID).block();

        assertThat(actualStartTimes).containsOnlyKeys(expectedIds);
    }

    private void batchUpdateTags(Set<UUID> traceIds) {
        traceResourceClient.batchUpdateTraces(TraceBatchUpdate.builder()
                .ids(traceIds)
                .update(TraceUpdate.builder().tags(BATCH_UPDATE_TAGS).build())
                .build(), API_KEY, WORKSPACE_NAME);
    }

    /** A one-item batch into a fresh dataset, referencing {@code traceId} - the shape that reaches the check. */
    private DatasetItemBatch datasetItemReferencing(UUID traceId) {
        var item = factory.manufacturePojo(DatasetItem.class).toBuilder()
                .source(DatasetItemSource.TRACE)
                .traceId(traceId)
                .spanId(null)
                .experimentItems(null)
                .build();

        return DatasetItemBatch.builder()
                .datasetName("dataset-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32)))
                .items(List.of(item))
                .build();
    }

    /**
     * A trace through the real ingestion path whose {@code id} carries {@code idAt}, in a project of its own.
     * {@code startTime} stays present-day: it is a separate column with its own range validation, and the id is what
     * this suite is about.
     */
    private Trace createTrace(Instant idAt) {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(idAt.toEpochMilli()))
                .startTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .endTime(null)
                .feedbackScores(null)
                .usage(null)
                .build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        return trace;
    }

    /**
     * The statement ClickHouse received for {@code queryName}, polled rather than read once: a query's
     * {@code query_log} row is queued asynchronously after the result reaches the client, and the flush interval this
     * container is configured with is 200 ms.
     */
    private String statementFor(String queryName, UUID traceId) {
        return Awaitility.await()
                .alias("query_log holds a %s statement mentioning id %s".formatted(queryName, traceId))
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> queryOneString(LAST_STATEMENT_FOR, statement -> statement
                        .bind("op", queryName)
                        .bind("trace_id", traceId.toString())), Objects::nonNull);
    }

    private String queryOneString(String sql, Consumer<Statement> binder) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, _) -> row.get(0, String.class))));
        }).block();
    }
}
