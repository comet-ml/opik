package com.comet.opik.infrastructure;

import com.comet.opik.api.Trace;
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
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.WeeklyPartitions;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Statement;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Exercises the partition pruning of {@code DELETE_BY_PROJECT_ID_TRACE_ID_PAIRS} against the post-EXCHANGE topology,
 * where {@code traces} is the weekly partitioned successor (OPIK-6901). {@code WeeklyPartitionsTest} covers the
 * derivation itself; what only a real ClickHouse can show is the half between it and the mutation — that the template
 * renders the predicate exactly when it should, that the derived {@code Long[]} binds to {@code IN :partitions}, and
 * that the row still goes away either way.
 *
 * <p>Each test asserts <b>both</b> halves, because either alone passes for the wrong reason: the rows are read back
 * through the public API (a delete that pruned to a partition the row is not in would leave it behind), and the SQL
 * ClickHouse actually received is read back from {@code system.query_log} (a delete that silently stopped pruning would
 * still remove the row, just slowly — the regression the flag and the derivation exist to prevent, and one no
 * behavioural assertion can see).
 *
 * <p>{@link #liveTracesIsTheWeeklyPartitionedSuccessor} is the guard that keeps the rest honest. The predicate is
 * harmless against an unpartitioned table for recent ids, so had the EXCHANGE below not taken effect every test here
 * would still pass while proving nothing; it pins both facts
 * {@code databaseAnalyticsDataModel.tracesWeeklyPartitionPruningEnabled} asserts — the weekly {@code PARTITION BY} and
 * {@code id_at} as {@code DateTime64}.
 *
 * <p>Two internal touches, on the pattern of {@code TracesDistributedWrapMutationTest}: the EXCHANGE has no public API,
 * so {@link #beforeAll} runs it in raw SQL identical to the swap block of {@code 000003_exchange_and_wrap.sql}; and the
 * ingestion path rejects a non-v7 or far-future {@code id} by design ({@code IdGenerator.validateId}), so the batches
 * that must <b>not</b> prune are handed to {@link TraceDAO#delete} directly — the only way to reach that arm.
 *
 * <p>Dedicated, non-reused ClickHouse and ZooKeeper containers are required because the EXCHANGE destructively swaps the
 * live {@code traces} table; a reused container would corrupt other suites and reruns.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesPartitionPruningMutationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    /**
     * The partition-key fragment the template emits. Compared verbatim against the SQL read back from
     * {@code system.query_log}, which is safe because that is the query text as submitted — the DAO's own template
     * string, not a re-print.
     */
    private static final String PARTITION_PREDICATE =
            "toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))";

    /** A v4 UUID: no timestamp to derive a partition from. */
    private static final UUID NON_V7_ID = UUID.fromString("9f527bac-527a-4f92-8875-0fa8af8e4f22");

    /**
     * A UUIDv7 whose 48 timestamp bits are all set (10889-08-02), so its {@code id_at} saturates to the
     * {@code DateTime64} ceiling and the honest week is not the partition the row would be in. Same rejection as
     * {@link #NON_V7_ID}, different cause — see {@code WeeklyPartitions}.
     */
    private static final UUID OUT_OF_RANGE_ID = UUID.fromString("ffffffff-ffff-7abc-8000-000000000001");

    // Dedicated, non-reused ClickHouse + ZooKeeper on their own network: the EXCHANGE destructively swaps `traces`, so a
    // shared/reused container would corrupt other suites and reruns. Redis/MySQL are only read, so the shared ones are
    // fine.
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
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer)
                .join();
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
                        // Both flags as production runs them post-EXCHANGE: the successor's end_time/ttft are
                        // non-nullable sentinel columns, and the pruning flag asserts the schema this suite installs.
                        .customConfigs(List.of(
                                new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                                new CustomConfig("databaseAnalyticsDataModel.tracesWeeklyPartitionPruningEnabled",
                                        "true")))
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private TransactionTemplateAsync template;
    private TraceDAO traceDAO;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template, TraceDAO traceDAO) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.template = template;
        this.traceDAO = traceDAO;
        exchangeTables();
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    @Test
    @DisplayName("the live traces table is the weekly partitioned successor")
    void liveTracesIsTheWeeklyPartitionedSuccessor() {
        // Both halves of what tracesWeeklyPartitionPruningEnabled asserts. Without this the whole suite is vacuous: against
        // the legacy unpartitioned `traces` a pruned delete of a recent id still removes the row, so every behavioural
        // assertion below would stay green while the predicate was being emitted at exactly the table it must not be.
        // Asserted piecewise rather than against PARTITION_PREDICATE verbatim: system.tables reports the expression as
        // ClickHouse's own formatter re-prints it, so pinning its whitespace would make this brittle about the one thing
        // it does not care about. No other partition expression in the schema is built from these three functions.
        assertThat(queryOneString("SELECT partition_key FROM system.tables WHERE database = currentDatabase()"
                + " AND name = 'traces'"))
                .as("traces is partitioned by the weekly id_at expression")
                .contains("toYYYYMMDD", "toDate32(id_at)", "toIntervalDay", "toDayOfWeek(id_at");
        assertThat(queryOneString("SELECT type FROM system.columns WHERE database = currentDatabase()"
                + " AND table = 'traces' AND name = 'id_at'"))
                .as("id_at is the 64-bit column, so a far-future timestamp is honest rather than wrapped")
                .isEqualTo("DateTime64(0, 'UTC')");
    }

    @Test
    @DisplayName("an all-UUIDv7 delete prunes to the batch's own partitions and removes the target row")
    void allUuidV7DeletePrunesAndRemovesTheTargetRow() {
        var target = newTrace().build();
        // Same project, so one read shows both: the pruned delete must take the target and leave this one.
        var bystander = newTrace().projectName(target.projectName()).build();
        traceResourceClient.createTrace(target, API_KEY, WORKSPACE_NAME);
        traceResourceClient.createTrace(bystander, API_KEY, WORKSPACE_NAME);
        assertThat(traceIdsOf(target.projectName())).contains(target.id(), bystander.id());

        // The live user path, end to end.
        traceResourceClient.deleteTrace(target.id(), WORKSPACE_NAME, API_KEY);

        assertThat(traceIdsOf(target.projectName()))
                .as("only the target row is gone")
                .doesNotContain(target.id())
                .contains(bystander.id());
        assertThat(lastTraceDeleteSql())
                .as("the mutation bounded itself to the target's own partition")
                .contains(PARTITION_PREDICATE)
                .contains(onlyPartitionOf(target.id()));
    }

    @Test
    @DisplayName("a batch spanning two weeks binds both partitions, not a range")
    void batchSpanningTwoWeeksBindsBothPartitions() {
        // The multi-value Long[] bind, which the single-id path never exercises. The second id is minted for a week
        // three years back and matches no row — the batch's partition SET is what is under test, and a delete does not
        // need its ids to exist. A range over the span would have selected every partition in between; the set names
        // exactly two.
        var target = newTrace().build();
        traceResourceClient.createTrace(target, API_KEY, WORKSPACE_NAME);
        var projectId = projectIdOf(target);
        var otherWeekId = UUID.fromString("018c1860-1800-7abc-8000-000000000001"); // id_at 2023-11-29 -> 20231127

        delete(Set.of(Pair.of(projectId, target.id()), Pair.of(projectId, otherWeekId)));

        assertThat(traceIdsOf(target.projectName())).doesNotContain(target.id());
        assertThat(lastTraceDeleteSql())
                .contains(PARTITION_PREDICATE)
                .contains(onlyPartitionOf(target.id()))
                .contains("20231127");
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("an id with no derivable partition disables pruning for the batch, and the delete still lands")
    void underivableIdDisablesPruning(String cause, UUID underivableId) {
        // The fallback that preserves the pre-OPIK-6901 guarantee: one id whose partition cannot be derived exactly and
        // the statement goes back to its unbounded form — no predicate at all, never a partial set. The v7 row batched
        // alongside it must still be deleted, which is the "not silently skipped" half.
        var target = newTrace().build();
        traceResourceClient.createTrace(target, API_KEY, WORKSPACE_NAME);
        var projectId = projectIdOf(target);

        delete(Set.of(Pair.of(projectId, target.id()), Pair.of(projectId, underivableId)));

        assertThat(traceIdsOf(target.projectName()))
                .as("the deletable row in a %s batch is still deleted", cause)
                .doesNotContain(target.id());
        assertThat(lastTraceDeleteSql())
                .as("no partition predicate is emitted for a %s batch", cause)
                .doesNotContain("toDayOfWeek");
    }

    private static Stream<Arguments> underivableIdDisablesPruning() {
        return Stream.of(
                arguments("non-v7", NON_V7_ID),
                arguments("beyond-2299", OUT_OF_RANGE_ID));
    }

    /**
     * The partition the id resolves to, as it appears in the SQL. Derived through {@code WeeklyPartitions} on purpose:
     * this suite is about the predicate reaching ClickHouse, and the derivation's own expected values are pinned against
     * real ClickHouse output in {@code WeeklyPartitionsTest}, so restating them here would only duplicate that.
     */
    private static String onlyPartitionOf(UUID id) {
        return String.valueOf(WeeklyPartitions.of(List.of(id)).orElseThrow().iterator().next());
    }

    /** Invokes the DAO under a workspace/user context, as {@code TraceService} does for the live delete path. */
    private void delete(Set<Pair<UUID, UUID>> projectIdTraceIdPairs) {
        template.nonTransaction(connection -> traceDAO.delete(projectIdTraceIdPairs, connection))
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER))
                .block();
    }

    /**
     * The SQL of the most recent trace delete, as ClickHouse received it. {@code log_comment} is what makes this
     * unambiguous: {@code TraceDAO} stamps every statement with {@code <query_name>:<workspace>:<user>:<details>}, and
     * {@code delete_traces} names this one template alone.
     */
    private String lastTraceDeleteSql() {
        execute("SYSTEM FLUSH LOGS", _ -> {
        });
        return queryOneString("""
                SELECT query
                FROM system.query_log
                WHERE log_comment LIKE 'delete_traces:%'
                AND type = 'QueryFinish'
                ORDER BY event_time_microseconds DESC
                LIMIT 1
                """);
    }

    /**
     * The EXCHANGE (000003 exchange block): puts the successor under {@code traces} and the original under
     * {@code traces_local_v2}, then a RENAME parks the original as {@code traces_pre_cutover_backup}. The wrap is
     * deliberately not applied — it is a separate, deferrable step, and the flag under test must hold on its own between
     * the two (which is why it is not the wrap flag). Kept identical to the cutover SQL by eye, as
     * {@code TracesLocalV2CutoverTest.exchangeTables} and the wrap suite do.
     */
    private void exchangeTables() {
        execute("EXCHANGE TABLES traces AND traces_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });
        execute("RENAME TABLE traces_local_v2 TO traces_pre_cutover_backup ON CLUSTER '{cluster}'", _ -> {
        });
    }

    /**
     * A trace with every trace-table column populated. Only the span-derived aggregates podam would otherwise
     * fabricate ({@code feedbackScores}, {@code usage}) are nulled, since they are not columns of the {@code traces}
     * table and would only add noise. The generated {@code projectName} is fresh per trace, so a test that wants two
     * traces in one project has to say so; that keeps one test's rows out of another's reads.
     */
    private Trace.TraceBuilder newTrace() {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null);
    }

    private UUID projectIdOf(Trace trace) {
        return traceResourceClient
                .getTraces(trace.projectName(), null, API_KEY, WORKSPACE_NAME, List.of(), List.of(), 100, Map.of())
                .content().stream()
                .filter(found -> found.id().equals(trace.id()))
                .map(Trace::projectId)
                .findFirst()
                .orElseThrow();
    }

    private List<UUID> traceIdsOf(String projectName) {
        return traceResourceClient
                .getTraces(projectName, null, API_KEY, WORKSPACE_NAME, List.of(), List.of(), 100, Map.of())
                .content().stream()
                .map(Trace::id)
                .toList();
    }

    private String queryOneString(String sql) {
        return template.nonTransaction(connection -> Mono
                .from(connection.createStatement(sql).execute())
                .flatMap(result -> Mono.from(result.map((row, _) -> row.get(0, String.class)))))
                .block();
    }

    private void execute(String sql, Consumer<Statement> binder) {
        template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute()).flatMap(result -> Mono.from(result.getRowsUpdated()));
        }).block();
    }
}
