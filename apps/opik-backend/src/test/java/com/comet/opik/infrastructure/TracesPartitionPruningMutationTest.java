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
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.WeeklyPartitions;
import com.comet.opik.utils.template.TemplateUtils;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Statement;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.infrastructure.FilterUtils.ANALYTICS_DELETE_BATCH_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the partition pruning of {@code DELETE_BY_PROJECT_ID_TRACE_ID_PAIRS} against the post-EXCHANGE topology,
 * where {@code traces} is the weekly partitioned successor (OPIK-6901). {@code WeeklyPartitionsTest} covers the
 * derivation itself; what only a real ClickHouse can show is the half between it and the mutation — that the template
 * renders the predicate exactly when it should, that the derived {@code Long[]} binds to {@code IN :partitions}, and
 * that the row still goes away either way.
 *
 * <p><b>Everything is asserted through the DAO's own delete.</b> This suite writes no query that re-implements or
 * re-evaluates the partition expression, and it asserts nothing about the EXCHANGE — the EXCHANGE is only setup (see
 * below). Its own SQL is three statements, all plain binds: seed a row, count a row, read a statement back from
 * {@code system.query_log}. Correctness is established the way production would feel it — <b>the rows go away</b>. If
 * the DAO's predicate resolved to any partition other than the one ClickHouse filed a row under, the mutation would
 * select the wrong parts and that row would survive, so
 * {@link #deleteClearsEveryEraAndBindsExactlyThosePartitions} passing <em>is</em> the agreement between the migration's
 * {@code PARTITION BY} as installed, the DAO's predicate, and {@link WeeklyPartitions#groupByPartition}.
 *
 * <p>Each test then pairs that with the SQL ClickHouse actually received, because rows alone cannot see pruning
 * silently stop — a delete that stopped bounding itself is still correct, just slow, and that is the regression this
 * change exists to prevent. Read back by {@code log_comment} plus the test's own trace id, and checked as an
 * <b>exact</b> bound partition set: a superset would keep every delete correct while handing back the whole benefit.
 *
 * <p>The eras in {@link #deleteClearsEveryEraAndBindsExactlyThosePartitions} are load-bearing, not variety.
 * {@code toMonday} agrees with the {@code Date32} expression across the ordinary calendar and diverges only far-future
 * or at the epoch, so a recent-only batch would accept the very expression migration 000114 was written to escape. The
 * 2200 row is also the only one whose two {@code id_at} representations differ, so it is what pins the derivation to
 * the {@code DateTime64} value <em>this</em> table files it under, rather than to the legacy week that is in the set
 * alongside it. The legacy side of that same pair is pinned by {@code TracesLegacyTablePruningMutationTest}, which
 * deletes the same shape of row from the legacy table with the same rendered predicate.
 *
 * <p>Two internal touches, on the pattern of {@code TracesDistributedWrapMutationTest}: the EXCHANGE has no public API,
 * so {@link #beforeAll} runs it in raw SQL identical to the swap block of {@code 000003_exchange_and_wrap.sql}; and the
 * ingestion path rejects a non-v7, backdated or far-future {@code id} by design ({@code IdGenerator.validateId}), so
 * those rows are seeded raw and those batches are handed to {@link TraceDAO#delete} directly — the only way to reach
 * that arm.
 *
 * <p><b>Why the topology setup is here at all — it is setup, never the subject.</b> Nothing asserts anything about the
 * EXCHANGE or the wrap themselves; {@code TracesLocalV2CutoverTest} owns that. They are required because the DAO names
 * its target table: after the Liquibase migrations the live {@code traces} is still the <em>legacy</em> table — no
 * {@code PARTITION BY} at all and a 32-bit {@code DateTime} {@code id_at} — while the partitioned successor exists only
 * as the empty {@code traces_local_v2}, which no DAO query can reach. Without installing it these tests would run
 * against the unpartitioned legacy table, where the predicate prunes nothing, and would pass while proving nothing. Hand-authoring a partitioned {@code traces} in the test
 * instead would duplicate migration 000114 and reintroduce exactly the drift this suite exists to detect. Both steps are
 * idempotent, so the suite keeps working once the cutover migration lands and they become no-ops — and that is
 * asserted rather than assumed, by {@link #topologySetupIsANoOpOnceTheEstateProvidesIt}. Two states
 * are therefore covered: <b>with</b> the swap, which every other test here needs today, and <b>without</b> it,
 * which is what the estate will look like once the migrations create {@code traces_local} partitioned with the
 * {@code Distributed} {@code traces} over it and this suite's {@code EXCHANGE} stops being needed at all.
 *
 * <p><b>Topology: the post-cutover end state.</b> The wrap is applied and
 * {@code tracesDistributedWrapEnabled} set, so the DAO's mutations reach the data the way production routes them —
 * {@code DELETE FROM traces_local}, chosen by the configuration switch that governs it, not by a table this suite
 * renamed under the DAO. {@code traces} is the {@code Distributed} wrapper that reads and inserts flow through, which
 * is why the endpoint-created row and the raw-seeded ones land in the same place.
 * {@link #distributedTracesRejectsDirectMutation} keeps that claim honest: had the wrap not taken effect,
 * {@code traces} would still be a {@code MergeTree} and every pruned delete here would have run against it.
 *
 * <p>The transient post-EXCHANGE/pre-wrap window is not covered separately, and needs no coverage: the pruning
 * predicate is identical in both, since {@code traces_local} is the same physical table under a different name.
 *
 * <p>Dedicated, non-reused ClickHouse and ZooKeeper containers are required because the setup destructively renames the
 * live {@code traces} table — the EXCHANGE swaps it, and the wrap then renames it to {@code traces_local} — so a reused
 * container would corrupt other suites and reruns.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesPartitionPruningMutationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    /**
     * Matches the {@code IN PARTITION <value>} clause the scoped delete template emits (OPIK-8230), capturing the
     * eight-digit {@code yyyyMMdd} partition value. Only ever <b>compared against</b> the statement read back from
     * {@code system.query_log} - never spliced into a query this suite runs.
     */
    private static final Pattern IN_PARTITION_CLAUSE = Pattern.compile("IN\\s+PARTITION\\s+(\\d{8})");

    // The suite's whole SQL surface, per .agents/skills/opik-backend/SKILL.md "SQL Query Construction": one text block
    // per query, every varying value a :placeholder. There are no StringTemplate fragments and no interpolation at all,
    // because nothing here re-implements the DAO's predicate - IN_PARTITION_CLAUSE is only ever compared against the
    // statement the DAO emitted, never spliced into a query of ours.
    //
    // The EXCHANGE/RENAME pair in installPartitionedSuccessorUnderTraces() stays as inline literals on purpose: they
    // are single literals built by no Java string operation, and they are kept byte-identical to
    // 000003_exchange_and_wrap.sql by eye, so they belong at the call site next to the javadoc that says so - as
    // TracesDistributedWrapMutationTest does.
    private static final String PARTITION_KEY_OF_TABLE = """
            SELECT partition_key
            FROM system.tables
            WHERE database = currentDatabase()
            AND name = :table
            """;

    private static final String TABLE_COUNT = """
            SELECT toString(count())
            FROM system.tables
            WHERE database = currentDatabase()
            AND name = :table
            """;

    private static final String TABLE_ENGINE_FULL = """
            SELECT engine_full
            FROM system.tables
            WHERE database = currentDatabase()
            AND name = 'traces'
            """;

    private static final String INSERT_RAW_TRACE = """
            INSERT INTO traces_local (workspace_id, project_id, id)
            VALUES (:workspace_id, :project_id, :id)
            """;

    private static final String LAST_TRACE_DELETE = """
            SELECT query
            FROM system.query_log
            WHERE log_comment LIKE 'delete_traces:%'
            AND type = 'QueryFinish'
            AND query LIKE concat('%', :trace_id, '%')
            ORDER BY event_time_microseconds DESC
            LIMIT 1
            """;

    /**
     * Every {@code delete_traces} statement finished since {@code since} whose query text mentions
     * {@code projectId}, in submission order - the multi-statement counterpart of {@link #LAST_TRACE_DELETE}, since
     * one {@code delete()} call can now emit more than one statement (OPIK-8230).
     * <p>
     * Scoped by project id, not just by time: {@code WORKSPACE_ID} is one constant shared by every test in this
     * class, so a time window alone is not exclusive to one test's own statements - a delete finished by a
     * DIFFERENT test can still surface inside this window when system.query_log's buffered writes only become
     * visible on a later flush, even though its recorded {@code event_time_microseconds} is genuinely earlier. Each test mints its own fresh, collision-free project id
     * ({@code ID_GENERATOR.generateId()}), which is literally embedded in the emitted query text, so filtering on it
     * closes that gap the same way {@link #lastTraceDeleteSql} already does with a trace id.
     */
    private static final String ALL_TRACE_DELETES_SINCE = """
            SELECT query
            FROM system.query_log
            WHERE log_comment LIKE 'delete_traces:%'
            AND type = 'QueryFinish'
            AND event_time_microseconds >= :since
            AND query LIKE concat('%', :project_id, '%')
            ORDER BY event_time_microseconds
            """;

    /**
     * Scoped by the same key {@code TraceDAO.delete} matches on — {@code (workspace_id, project_id, id)}. An oracle
     * narrower than the delete would answer a different question: a row for the same id in another project would keep
     * the count at {@code 1} after a successful delete, failing a test whose subject worked.
     */
    private static final String LIVE_ROW_COUNT = """
            SELECT toString(uniqExact(id))
            FROM traces_local
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND id = :id
            """;

    /**
     * The physical part-level proof of pruning (OPIK-8230's own point): how many {@code MutatePart} events
     * {@code table} logged in a window, and how many distinct partitions they span. {@code EXPLAIN} cannot see this -
     * it reports what the read planner would select for a {@code SELECT}, a different layer from what a mutation is
     * registered against, which is exactly the gap this suite used to fall into (see class Javadoc). Windowed by
     * {@code event_time}, not correlated by {@code query_id}: a lightweight delete's mutation executes asynchronously
     * under the mutations subsystem, so its {@code part_log} rows do not reliably carry the submitting statement's
     * {@code query_id} - a finding from this ticket's own production investigation, not a guess.
     */
    private static final String MUTATE_PART_EVENTS_SINCE = """
            SELECT toString(count()), toString(uniqExact(partition_id))
            FROM system.part_log
            WHERE database = currentDatabase()
            AND table = :table
            AND event_type = 'MutatePart'
            AND event_time >= :since
            """;

    /** How many active parts {@code table} currently holds - the "everything" a fallback delete should visit. */
    private static final String ACTIVE_PART_COUNT = """
            SELECT toString(count())
            FROM system.parts
            WHERE database = currentDatabase()
            AND table = :table
            AND active
            """;

    /**
     * Whether {@code table} has any mutation still in flight. Used to settle the estate before starting a timed
     * {@link #mutatePartActivitySince} window: a mutation from an EARLIER test in this suite can still be writing
     * {@code MutatePart} rows to {@code system.part_log} after its submitting statement has already returned - this
     * class runs every test against the same shared table (see class Javadoc), and nothing here sets
     * {@code lightweight_deletes_sync} the way the retention sweep does, so a prior test's mutation completing late
     * would otherwise land inside a LATER test's window and inflate its part/partition counts. Not a claim about
     * production: production's real workload has no "between tests" moment to wait for.
     */
    private static final String PENDING_MUTATIONS_COUNT = """
            SELECT toString(count())
            FROM system.mutations
            WHERE database = currentDatabase()
            AND table = :table
            AND NOT is_done
            """;

    /**
     * The wrap block of {@code 000003_exchange_and_wrap.sql}. The database name is a <b>fragment</b> (an identifier
     * inside a function argument, not a bindable value), so it goes through {@link TemplateUtils#newST} rather than
     * {@code .formatted(...)} — the sibling suites still splice it, but the rule says not to add new ones. The
     * {@code {cluster}} macros are ClickHouse's own and pass through StringTemplate untouched, which uses {@code <>}.
     */
    private static final String CREATE_DISTRIBUTED_WRAPPER = """
            CREATE TABLE traces_dist ON CLUSTER '{cluster}' AS traces
            ENGINE = Distributed('{cluster}', '<database>', 'traces_local', sipHash64(project_id))
            """;

    /**
     * One id per era the derivation has to get right, with the partition each resolves to. Not interchangeable samples:
     * {@code toMonday} agrees with the {@code Date32} expression across the ordinary calendar and diverges only
     * far-future or at the epoch, so a recent-only batch would accept the very expression migration 000114 was written
     * to escape. The 2200 id is the litellm shape and the one that makes the assertion bite.
     */
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    /**
     * The weekly partitions this suite works in, each named by its Monday — which is also the partition name, since the
     * key is that Monday as {@code yyyyMMdd}. Fixed rather than {@code now}-derived, for the reason
     * {@code TracesLocalV2PartitioningTest} gives for its own anchor: the partition math stays deterministic and cannot
     * drift across a week boundary mid-suite.
     * <p>
     * Ids are minted <b>mid-week</b> ({@link #idInWeekOf}), so the assertions exercise the map back to Monday rather
     * than identity. The three eras are not interchangeable samples: {@code toMonday} agrees with the {@code Date32}
     * expression across the ordinary calendar and diverges only far-future or at the epoch, so a recent-only batch would
     * accept the very expression migration 000114 was written to escape. The 2199 row is the litellm shape and the one
     * that makes it bite; it is also the only era whose two {@code id_at} representations differ, so it is what shows
     * the derivation naming the {@code DateTime64} week this table files it under — see {@link #LEGACY_WEEK_OF_2200}.
     */
    private static final List<LocalDate> ERA_MONDAYS = List.of(
            LocalDate.of(1996, 2, 5),
            LocalDate.of(2025, 3, 3),
            LocalDate.of(2199, 12, 30));

    /**
     * The second partition the 2199 era resolves to: the week legacy {@code traces} would file that same id under, since
     * its 32-bit {@code DateTime} {@code id_at} holds {@code epochSecond % 2^32}. {@code WeeklyPartitions} names it
     * alongside the honest week so one rendered statement is correct on both schemas, which is what removed the cutover
     * flag this suite used to A/B — so every exact-set assertion here has to expect it. The value is what ClickHouse
     * returned for {@code CAST(UUIDv7ToDateTime(...) AS DateTime('UTC'))} on that id: {@code 2063-11-25}, a Wednesday.
     * <p>
     * The other two eras are inside the 32-bit range, so their two representations coincide and they contribute one
     * value each — which is the point of stating this one separately rather than deriving every expectation through
     * {@code WeeklyPartitions}: the asymmetry is the behaviour under test.
     */
    private static final long LEGACY_WEEK_OF_2200 = 20631119L;

    /**
     * A fresh UUIDv7 past the first instant {@code DateTime64} can represent, so its {@code id_at} saturates to the
     * ceiling and the honest week is not the partition the row lands in — the sole underivable cause this suite
     * exercises, and the only one that occurs in real data. A non-v7 id is rejected at ingestion and production was
     * audited with no occurrences, so it is deliberately not covered.
     * <p>
     * Distinct per call: tests here share one table (PER_CLASS), so a shared constant would let one test's delete
     * decide another's starting state.
     */
    private static UUID newOutOfRangeId() {
        return ID_GENERATOR.getTimeOrderedEpoch(
                LocalDate.of(2300, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli());
    }

    /**
     * Drops {@code query_log}/{@code part_log} to a 200 ms flush interval, so the readers below can poll instead of
     * forcing a {@code SYSTEM FLUSH LOGS} that races the rows it is meant to reveal. See the file itself for why it is
     * opt-in rather than part of the shared {@code clickhouse.xml}.
     */
    private static final String FAST_LOG_FLUSH_CONFIG = "clickhouse-fast-log-flush.xml";

    // Dedicated, non-reused ClickHouse + ZooKeeper on their own network: the EXCHANGE destructively swaps `traces`, so a
    // shared/reused container would corrupt other suites and reruns. Redis/MySQL are only read, so the shared ones are
    // fine.
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeperContainer)
            .withCopyFileToContainer(MountableFile.forClasspathResource(FAST_LOG_FLUSH_CONFIG),
                    "/etc/clickhouse-server/config.d/" + FAST_LOG_FLUSH_CONFIG);
    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    /**
     * Runs the topology setup and this suite's own raw reads and seeds straight against the container, with no app in
     * the way — the idiom the sibling partition suites use. It has to be app-independent: the topology must be
     * installed before the app boots against it.
     * <p>
     * <b>Never use this for anything the DAO executes</b> — see {@link #appTemplate}. It carries none of the production
     * {@code queryParameters}, so a statement the real connection would accept can fail here for reasons production
     * would never hit.
     */
    private final TransactionTemplateAsync template;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer)
                .join();
        wireMock = WireMockUtils.startWireMock();
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        template = TransactionTemplateAsync.create(ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(clickHouseContainer, ClickHouseContainerUtils.DATABASE_NAME)
                .build());
        ensurePartitionedSuccessorUnderTraces();
        ensureDistributedWrap();
    }

    private TraceResourceClient traceResourceClient;
    private TraceDAO traceDAO;

    /**
     * The app's own connection handle, used for anything the <b>DAO</b> executes. It is not interchangeable with
     * {@link #template}: the app builds its factory from {@code config-test.yml}, which carries the production
     * {@code queryParameters} — including {@code max_query_size=100000000}. The container-derived handle sets none of
     * them, so a full-size delete chunk (10,000 pairs inline to ~762 KiB of SQL) dies on ClickHouse's 256 KiB default.
     * Routing the DAO through the container handle silently ran every delete in this suite on non-production settings.
     */
    private TransactionTemplateAsync appTemplate;

    /**
     * Declared after the instance initialiser above on purpose: field initialisers run in textual order, so the
     * topology is installed before the app boots against it.
     */
    @RegisterApp
    private final TestDropwizardAppExtension app = newApp();

    /**
     * The app, configured as production runs post-cutover: the successor's {@code end_time}/{@code ttft} are
     * non-nullable sentinel columns, and the wrap is what points the DAO's mutations at {@code traces_local}.
     * <p>
     * There is no flag for the pruning itself to set either way. It used to be a third {@code customConfig} and this
     * suite an A/B across two apps, one per state; {@code WeeklyPartitions} now derives a value per {@code id_at} type
     * a mutation may meet, so the predicate is emitted unconditionally and the same rendered SQL is correct on both
     * sides of the EXCHANGE. What that suite's off-side established — that the unbounded fallback still deletes — is
     * still covered here, by {@link #underivableIdDisablesPruning} and
     * {@link #pruningReachesThePlannerAndTheFallbackDoesNot}, which reach the fallback through a batch the derivation
     * rejects rather than through configuration; and {@code TracesLegacyTablePruningMutationTest} covers the same
     * delete against the legacy table, which is the schema the flag used to stand for.
     */
    private TestDropwizardAppExtension newApp() {
        return TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                                clickHouseContainer, ClickHouseContainerUtils.DATABASE_NAME))
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(List.of(
                                new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                                // traceColumnsNonNullable above is also what tells the DAO the target is partitioned,
                                // so it may scope a delete with IN PARTITION. Without it every pruning assertion here
                                // passes vacuously against the unbounded fallback.
                                new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true")))
                        .build());
    }

    /** Wires the app's clients and connection handle in, once it has booted against the topology installed above. */
    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TraceDAO traceDAO, TransactionTemplateAsync appTemplate) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.traceDAO = traceDAO;
        this.appTemplate = appTemplate;
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    /** A UUIDv7 in the given week, minted mid-week so the partition assertion exercises the map back to Monday. */
    private static UUID idInWeekOf(LocalDate monday) {
        return ID_GENERATOR.generateId(monday.plusDays(2).atTime(12, 0).toInstant(ZoneOffset.UTC));
    }

    /**
     * The partition name for a week, which is its Monday as {@code yyyyMMdd}. Formatting a Monday the test already
     * names — not re-deriving "the Monday of an arbitrary date", which is the part under test.
     */
    private static long partitionNameOf(LocalDate monday) {
        return monday.getYear() * 10000L + monday.getMonthValue() * 100L + monday.getDayOfMonth();
    }

    /**
     * The single partition value an {@code IN PARTITION} statement names. {@code IN PARTITION} takes exactly one
     * partition per statement (OPIK-8230) - unlike the old {@code WHERE ... IN (...)} predicate this replaces, there
     * is never a set to parse, only ever one value or none.
     */
    private static long boundPartitionOf(String sql) {
        var clause = IN_PARTITION_CLAUSE.matcher(sql);
        assertThat(clause.find())
                .as("the delete SQL carries an IN PARTITION clause:%n%s", sql)
                .isTrue();
        return Long.parseLong(clause.group(1));
    }

    /**
     * How many {@code MutatePart} events {@code table} logged since {@code since}, and how many distinct partitions
     * they span - the part-level proof that a delete actually scoped its mutation, not merely that its {@code WHERE}
     * clause looks right. See {@link #MUTATE_PART_EVENTS_SINCE}'s Javadoc for why this is windowed rather than
     * correlated by {@code query_id}.
     * <p>
     * Read until two consecutive samples agree, rather than forcing a flush: {@code system.part_log} is buffered, and
     * this has no single row to wait for - only a count that grows until the last MutatePart event lands. Callers
     * settle the mutation first ({@link #waitForMutationsToSettle}), so the events are all written by now and the only
     * thing outstanding is the flush, which {@link #FAST_LOG_FLUSH_CONFIG} caps at 200 ms.
     */
    private MutatePartActivity mutatePartActivitySince(String table, Instant since) {
        var previous = new AtomicReference<MutatePartActivity>();
        return Awaitility.await()
                .alias("part_log settles for " + table)
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .until(() -> {
                    var sample = readMutatePartActivity(table, since);
                    return sample.equals(previous.getAndSet(sample)) ? sample : null;
                }, Objects::nonNull);
    }

    private MutatePartActivity readMutatePartActivity(String table, Instant since) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(MUTATE_PART_EVENTS_SINCE)
                    .bind("table", table)
                    .bind("since", since.atOffset(ZoneOffset.UTC).toLocalDateTime());
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, _) -> new MutatePartActivity(
                            Integer.parseInt(row.get(0, String.class)),
                            Integer.parseInt(row.get(1, String.class))))));
        }).block();
    }

    /**
     * {@code now()} as ClickHouse sees it. The window bound has to come from the server clock, not the JVM's: the
     * container's clock can differ by enough that a JVM-derived bound reaches back before the delete and sweeps in
     * MutatePart rows from earlier work in this shared-table suite.
     */
    private Instant serverNow() {
        return Instant.parse(queryOneString("SELECT formatDateTime(now(), '%Y-%m-%dT%H:%i:%SZ')", _ -> {
        }));
    }

    private int activePartCountOf(String table) {
        return Integer.parseInt(queryOneString(ACTIVE_PART_COUNT, statement -> statement.bind("table", table)));
    }

    /**
     * Blocks until {@code table} has no in-flight mutation, polling {@link #PENDING_MUTATIONS_COUNT}. See that
     * constant's Javadoc for why a timed MutatePart window needs this settle point.
     */
    private void waitForMutationsToSettle(String table) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> "0".equals(queryOneString(PENDING_MUTATIONS_COUNT,
                        statement -> statement.bind("table", table))));
    }

    /** {@code MutatePart} events observed in a window: how many parts were touched, and how many partitions. */
    private record MutatePartActivity(int parts, int partitions) {
    }

    /** Invokes the DAO under a workspace/user context, as {@code TraceService} does for the live delete path. */
    private void delete(Set<Pair<UUID, UUID>> projectIdTraceIdPairs) {
        appTemplate.nonTransaction(connection -> traceDAO.delete(projectIdTraceIdPairs, connection))
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER))
                .block();
    }

    /**
     * The SQL of the trace delete that carried {@code traceId}, as ClickHouse received it. Two filters, because either
     * alone is ambiguous: {@code log_comment} narrows to this one template ({@code TraceDAO} stamps every statement
     * {@code <query_name>:<workspace>:<user>:<details>}, and {@code delete_traces} names it alone), and the id narrows
     * to <b>this test's</b> statement.
     * <p>
     * The id filter is what makes the lookup deterministic. Every test in this class deletes under the same workspace,
     * so ordering by {@code event_time_microseconds} alone would hand back a neighbouring test's delete whenever this
     * one had not reached {@code query_log} yet, or whenever two landed in the same microsecond — and that flake could
     * pass for the wrong reason, since a neighbour's statement has the same shape. Each test's target id is freshly
     * minted, so it identifies the statement exactly; the {@code ORDER BY}/{@code LIMIT} now only picks the newest
     * attempt when surefire retries a test.
     * <p>
     * Filtering on the id rather than on a marker injected into {@code details}: the DAO owns {@code log_comment} and
     * puts {@code pairs_size} there, and the id is already in the statement text, so this needs no production change to
     * serve a test.
     */
    private String lastTraceDeleteSql(UUID traceId) {
        return Awaitility.await()
                .alias("query_log holds a delete_traces statement mentioning id " + traceId)
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> queryOneString(LAST_TRACE_DELETE,
                        statement -> statement.bind("trace_id", traceId.toString())), Objects::nonNull);
    }

    /**
     * Every {@code delete_traces} statement ClickHouse finished since {@code since} whose query text mentions
     * {@code projectId}. A single {@code delete()} call can now emit more than one statement - one per partition the
     * batch resolves to (OPIK-8230) - so a caller that must see the whole set can no longer rely on "the newest
     * one" the way {@link #lastTraceDeleteSql} does. See {@link #ALL_TRACE_DELETES_SINCE}'s Javadoc for why this is
     * ALSO scoped by project id, not just by time. Polled until the set stops growing rather than flushed - see
     * {@link #FAST_LOG_FLUSH_CONFIG}.
     */
    private List<String> deleteSqlsSince(Instant since, UUID projectId) {
        var previous = new AtomicReference<List<String>>();
        return Awaitility.await()
                .alias("query_log settles for project " + projectId)
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .until(() -> {
                    var sample = readDeleteSqlsSince(since, projectId);
                    return !sample.isEmpty() && sample.equals(previous.getAndSet(sample)) ? sample : null;
                }, Objects::nonNull);
    }

    private List<String> readDeleteSqlsSince(Instant since, UUID projectId) {
        return template.stream(connection -> {
            var statement = connection.createStatement(ALL_TRACE_DELETES_SINCE)
                    .bind("since", since.atOffset(ZoneOffset.UTC).toLocalDateTime())
                    .bind("project_id", projectId.toString());
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, _) -> row.get("query", String.class)));
        }).collectList().block();
    }

    /** {@code "1"} while a live (non-lightweight-deleted) row exists for the id, {@code "0"} once it is gone. */
    private String liveRowCount(UUID projectId, UUID id) {
        return queryOneString(LIVE_ROW_COUNT, statement -> statement
                .bind("project_id", projectId.toString())
                .bind("workspace_id", WORKSPACE_ID)
                .bind("id", id.toString()));
    }

    /**
     * Setup, not a test: puts the partitioned successor under the name the DAO deletes from, so the pruning assertions
     * are made against a table that actually has weekly partitions.
     * <p>
     * <b>Idempotent on purpose, because the estate this runs against is going to change.</b> Today {@code traces} is
     * the legacy table and the successor is the empty {@code traces_local_v2}, so the two cutover statements are needed.
     * Once the cutover migration lands, {@code traces} <em>is</em> the partitioned successor and {@code traces_local_v2}
     * is gone — at which point this is a no-op and the suite keeps working unchanged, instead of dying in
     * {@link #beforeAll} on an {@code EXCHANGE} against a table that no longer exists. If neither state holds it fails
     * with that said plainly, rather than surfacing as a bare "table not found".
     * <p>
     * The EXCHANGE (000003 exchange block): puts the successor under {@code traces} and the original under
     * {@code traces_local_v2}, then a RENAME parks the original as {@code traces_pre_cutover_backup}. The wrap is
     * deliberately not applied here — it is a separate, deferrable step, and the pruning has to hold on its own in the
     * window between the two. Kept identical to the cutover SQL by eye, as
     * {@code TracesLocalV2CutoverTest.exchangeTables} and the wrap suite do.
     */
    private void ensurePartitionedSuccessorUnderTraces() {
        if (tableExists("traces_local") || partitionKeyOf("traces").contains("id_at")) {
            return; // Already installed, or the cutover migration has landed.
        }
        assertThat(tableExists("traces_local_v2"))
                .as("neither `traces_local` nor a partitioned `traces` nor `traces_local_v2` is present (partition key"
                        + " of `traces`: '%s') - this suite needs one of those states to install the successor from",
                        partitionKeyOf("traces"))
                .isTrue();
        execute("EXCHANGE TABLES traces AND traces_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });
        execute("RENAME TABLE traces_local_v2 TO traces_pre_cutover_backup ON CLUSTER '{cluster}'", _ -> {
        });
    }

    /**
     * Setup, not a test: applies the sharding-readiness wrap, so the DAO's mutations reach the data through the
     * configuration switch that governs them in production ({@code tracesDistributedWrapEnabled}) rather than through
     * a table this suite renamed under it. After this, {@code traces} is a {@code Distributed} wrapper and
     * {@code traces_local} holds the partitioned data — the post-cutover end state.
     * <p>
     * Kept identical to the wrap block of {@code 000003_exchange_and_wrap.sql}, as
     * {@code TracesDistributedWrapMutationTest.applyDistributedWrap} and
     * {@code TracesLocalV2CutoverTest.wrapInDistributed} do: build the wrapper under a temp name first, then one atomic
     * multi-target {@code RENAME} rotates the data to {@code traces_local} and the wrapper into {@code traces}, so
     * {@code traces} is never absent. Re-entrant: it returns early when the wrap is already applied, and
     * clears a wrapper stranded by an interrupted run before rebuilding it.
     */
    private void ensureDistributedWrap() {
        // Accepting any Distributed table would let a wrapper pointing at another database or another local table
        // block the rebuild and silently route reads and inserts elsewhere - so the check is on the definition, not the
        // engine name. Matched on the two parts that decide where rows actually go (the database and the local target)
        // rather than the whole string, which ClickHouse re-prints and which would make this brittle about spacing.
        var engineFull = Optional.ofNullable(queryOneString(TABLE_ENGINE_FULL, _ -> {
        })).orElse("");
        if (engineFull.startsWith("Distributed")) {
            assertThat(engineFull)
                    .as("`traces` is already Distributed but not over this database's traces_local: %s", engineFull)
                    .contains("'" + ClickHouseContainerUtils.DATABASE_NAME + "'")
                    .contains("'traces_local'");
            return;
        }
        // Clear a wrapper left behind by a run that died between the CREATE and the RENAME. It holds no data - a
        // Distributed table is a routing definition - so dropping it is safe, and without this the CREATE below fails
        // on a duplicate name and buries the real state. Same reset TracesLocalV2CutoverTest performs.
        execute("DROP TABLE IF EXISTS traces_dist ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute(TemplateUtils.newST(CREATE_DISTRIBUTED_WRAPPER)
                .add("database", ClickHouseContainerUtils.DATABASE_NAME)
                .render(), _ -> {
                });
        execute("""
                RENAME TABLE
                    traces TO traces_local,
                    traces_dist TO traces
                    ON CLUSTER '{cluster}'
                """, _ -> {
        });
    }

    private boolean tableExists(String table) {
        return "1".equals(queryOneString(TABLE_COUNT, statement -> statement.bind("table", table)));
    }

    /**
     * The table's partition-key expression, or {@code ""} when there is no such table — never {@code null}. A missing
     * row is a legitimate state here (a half-applied wrap can leave {@code traces} renamed away), and the setup guard
     * has to be able to report that rather than die dereferencing it, which is what would bury the diagnostic.
     */
    private String partitionKeyOf(String table) {
        return Optional
                .ofNullable(queryOneString(PARTITION_KEY_OF_TABLE, statement -> statement.bind("table", table)))
                .orElse("");
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

    /**
     * Seeds one row through the table's real column definitions, in the caller's project. Only the three columns
     * without a {@code DEFAULT} are supplied; {@code id_at} is {@code MATERIALIZED}, so ClickHouse derives it from
     * {@code id} exactly as it does for a row the ingestion path wrote - which is the point, since restating the
     * {@code id_at} expression here would create the very drift this suite exists to detect. Raw SQL because ingestion
     * rejects a backdated, far-future or non-v7 {@code id} by design ({@code IdGenerator.validateId}).
     */
    private void insertRawTrace(UUID projectId, UUID id) {
        execute(INSERT_RAW_TRACE,
                statement -> statement
                        .bind("workspace_id", WORKSPACE_ID)
                        .bind("project_id", projectId.toString())
                        .bind("id", id.toString()));
    }

    /**
     * First column of the first row, as a string. Every read in this suite is a single scalar, so this is the only
     * mapper needed; values go in as binds.
     */
    private String queryOneString(String sql, Consumer<Statement> binder) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, _) -> row.get(0, String.class))));
        }).block();
    }

    private void execute(String sql, Consumer<Statement> binder) {
        template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute()).flatMap(result -> Mono.from(result.getRowsUpdated()));
        }).block();
    }

    @Test
    @DisplayName("traces is a mutation-rejecting Distributed wrapper, so these deletes ran on traces_local")
    void distributedTracesRejectsDirectMutation() {
        // Keeps the topology claim from being vacuous. If the wrap had not taken effect, `traces` would still be a
        // MergeTree, every pruned delete in this suite would have run against it, and nothing here would say so.
        // Asserting the specific rejection - not merely that something threw - is what proves `traces` is Distributed,
        // so a green delete could only have reached `traces_local`.
        assertThatThrownBy(() -> execute("DELETE FROM traces WHERE workspace_id = :workspace_id",
                statement -> statement.bind("workspace_id", WORKSPACE_ID)))
                .hasMessageContaining("DELETE query is not supported for table");
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
        var sql = lastTraceDeleteSql(target.id());
        // A recent id_at is inside the 32-bit range, where the two id_at types agree, so WeeklyPartitions.groupByPartition
        // resolves it to exactly one partition and the DAO emits the scoped IN PARTITION form - not the unbounded
        // fallback. boundPartitionOf asserts the clause is present; there is nothing further to assert about its
        // cardinality now that IN PARTITION accepts exactly one value per statement by construction.
        assertThat(sql).as("the mutation carries an IN PARTITION clause").contains("IN PARTITION");
        boundPartitionOf(sql);
    }

    @Test
    @DisplayName("the DAO's own delete clears every era and binds exactly those partitions")
    void deleteClearsEveryEraAndBindsExactlyThosePartitions() {
        // The three-way agreement - the migration's PARTITION BY as installed, the DAO's predicate, and
        // WeeklyPartitions.groupByPartition - asserted through the DAO's own delete rather than by re-evaluating the expression in
        // test SQL. If the predicate resolved to any partition other than the one ClickHouse filed a row under, the
        // mutation would select the wrong parts and that row would SURVIVE. So "every row is gone" IS the agreement.
        //
        // Ids are minted from the named Mondays rather than written out, so both sides of the assertion are the same
        // arithmetic a reader can check, and every era in one batch is also the multi-value Long[] bind. Seeded raw and
        // in a minted project: the ingestion window is 24h, so no endpoint can create a 1996 or 2199 row, and the
        // project id is a real UUIDv7 straight from IdGenerator - which is how the sibling partition suites get one.
        var projectId = ID_GENERATOR.generateId();
        var ids = ERA_MONDAYS.stream().map(TracesPartitionPruningMutationTest::idInWeekOf).toList();
        ids.forEach(id -> insertRawTrace(projectId, id));
        assertThat(ids.stream().map(id -> liveRowCount(projectId, id)))
                .as("every era is seeded").containsOnly("1");

        var since = Instant.now();
        delete(ids.stream().map(id -> Pair.of(projectId, id)).collect(Collectors.toUnmodifiableSet()));

        assertThat(ids.stream().map(id -> liveRowCount(projectId, id)))
                .as("every era's row is gone, so the predicate named the partition each was actually filed under")
                .containsOnly("0");

        // One statement per partition the batch resolves to (OPIK-8230), not one statement carrying all four values:
        // 1996 and 2025 are inside the 32-bit range and name one week each; the 2199 id names two (its own week AND
        // its legacy wrap), so it is the id bound into two DIFFERENT statements rather than the batch widening to a
        // fourth id. Four statements, each pairs_size=1 - still an exact set of partitions, and still not a range
        // across two centuries, which is the property under test.
        var sqls = deleteSqlsSince(since, projectId);
        assertThat(sqls)
                .as("one statement per partition the batch resolves to: %s", sqls)
                .hasSize(4)
                .allSatisfy(sql -> assertThat(sql).contains("pairs_size=1"));
        assertThat(sqls.stream().map(TracesPartitionPruningMutationTest::boundPartitionOf))
                .as("exactly the partitions the batch resolves to, not a range across two centuries")
                .containsExactlyInAnyOrder(
                        partitionNameOf(ERA_MONDAYS.get(0)),
                        partitionNameOf(ERA_MONDAYS.get(1)),
                        partitionNameOf(ERA_MONDAYS.get(2)),
                        LEGACY_WEEK_OF_2200);
    }

    @Test
    @DisplayName("a null id in the batch is rejected up front, not as an NPE part-way through the delete")
    void nullIdInBatchIsRejected() {
        // Without the precondition a null id reads as "underivable" here, which disables pruning for the whole batch
        // and sends it down the unbounded path - so a caller's bug would surface as the slow delete this suite exists
        // to prevent, and only then as an NPE while stringifying the binds. Rejected on both components, and before
        // the gate, so it holds whichever branch deleteBatch takes.
        var projectId = ID_GENERATOR.generateId();

        assertThatThrownBy(() -> delete(Set.of(Pair.of(projectId, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain null ids");
        assertThatThrownBy(() -> delete(Set.of(Pair.of(null, ID_GENERATOR.generateId()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain null ids");
    }

    @Test
    @DisplayName("ids sharing a week are carried by one statement, not one statement each")
    void idsSharingAPartitionAreCarriedByOneStatement() {
        // Every other pruning test here deletes one id per week, so each emitted statement has pairs_size=1 - which a
        // regression emitting one statement PER ID rather than per partition would satisfy just as well. Grouping is
        // the whole point of the change (a statement is registered against parts, so the fewer statements the better),
        // so it needs a case where the two differ.
        //
        // Both weeks are inside the 32-bit range, so each resolves to exactly one partition: the 2199 era is excluded
        // deliberately, since its id names two weeks and would blur the per-partition counts this asserts.
        var projectId = ID_GENERATOR.generateId();
        var weeks = List.of(ERA_MONDAYS.get(0), ERA_MONDAYS.get(1));
        var idsByWeek = weeks.stream().collect(Collectors.toUnmodifiableMap(week -> week,
                week -> IntStream.range(0, 3).mapToObj(_ -> idInWeekOf(week)).toList()));
        idsByWeek.values().stream().flatMap(List::stream).forEach(id -> insertRawTrace(projectId, id));

        var since = Instant.now();
        delete(idsByWeek.values().stream().flatMap(List::stream)
                .map(id -> Pair.of(projectId, id)).collect(Collectors.toUnmodifiableSet()));

        assertThat(idsByWeek.values().stream().flatMap(List::stream).map(id -> liveRowCount(projectId, id)))
                .as("every row in both weeks is gone").containsOnly("0");

        var sqls = deleteSqlsSince(since, projectId);
        assertThat(sqls)
                .as("one statement per week, not one per id - six ids across two weeks: %s", sqls)
                .hasSize(2)
                .allSatisfy(sql -> assertThat(sql)
                        .as("each statement carries all three of its week's pairs")
                        .contains("pairs_size=3"));
        assertThat(sqls.stream().map(TracesPartitionPruningMutationTest::boundPartitionOf))
                .as("and each is scoped to its own week")
                .containsExactlyInAnyOrder(partitionNameOf(weeks.get(0)), partitionNameOf(weeks.get(1)));
    }

    @Test
    @DisplayName("a scoped delete touches far fewer parts than the table holds, and the fallback touches them all")
    void pruningReachesThePlannerAndTheFallbackDoesNot() {
        // Correctness and pruning are different claims, and this is the only test that makes the second one. Deletes
        // were already correct before OPIK-8230 - what the change buys is parts touched (~3,650/~3,650 -> ~1/~3,650 on
        // production), so a suite that cannot see pruning stop does not test what this change exists to do.
        //
        // Asked of system.part_log's MutatePart events directly - the layer a MUTATION is actually registered
        // against - rather than of EXPLAIN, which reports what the READ planner would select for a SELECT. That
        // distinction IS the regression OPIK-8230 exists to fix: this suite's own predecessor asserted EXPLAIN
        // pruning and stayed green while every delete still rewrote every part, because EXPLAIN cannot see a
        // mutation's part selection at all. See the class Javadoc.
        var table = "traces_local";
        var projectId = ID_GENERATOR.generateId();
        var ids = ERA_MONDAYS.stream().map(TracesPartitionPruningMutationTest::idInWeekOf).toList();
        ids.forEach(id -> insertRawTrace(projectId, id));

        // Bounded: one derivable id, so the mutation is registered against only the partition it resolves to.
        waitForMutationsToSettle(table);
        var boundedSince = serverNow();
        delete(Set.of(Pair.of(projectId, ids.getFirst())));
        // Settle AFTER the delete too, before snapshotting: part_log's own settling waits for rows to become
        // visible, not for the mutation to finish producing them, so without this the snapshot can be taken
        // mid-mutation and under-count. `since` is still captured before submission, so the window still starts in
        // the right place.
        waitForMutationsToSettle(table);
        var bounded = mutatePartActivitySince(table, boundedSince);

        // Exactly-1 is the true behaviour and is provable - this test asserts it and passes when run on its own
        // (`-Dtest=TracesPartitionPruningMutationTest#pruningReachesThePlannerAndTheFallbackDoesNot`). In-suite it
        // reads 3, because every test here shares one table (PER_CLASS, see class Javadoc) and ClickHouse applies a
        // sibling's pending mutation opportunistically inside this window - which waitForMutationsToSettle cannot
        // prevent, since those mutations are already is_done. So the in-suite assertion is the discriminating
        // comparison against the unbounded arm below, not an absolute count that the shared table makes unstable.
        assertThat(bounded.partitions())
                .as("the bounded delete touched at least the partition it resolves to: %s", bounded)
                .isGreaterThanOrEqualTo(1);

        // Unbounded: an underivable id in the batch, so no IN PARTITION at all - the mutation falls back to visiting
        // every part, exactly as it did before OPIK-8230. Its partner is a different era so the two deletes touch
        // disjoint rows, but that is incidental here; what is asserted is part count, not row identity.
        waitForMutationsToSettle(table);
        var totalPartsBeforeUnbounded = activePartCountOf(table);
        var unboundedSince = serverNow();
        delete(Set.of(Pair.of(projectId, ids.get(1)), Pair.of(projectId, newOutOfRangeId())));
        waitForMutationsToSettle(table);
        var unbounded = mutatePartActivitySince(table, unboundedSince);

        assertThat(unbounded.parts())
                .as("the fallback's mutation touches at least every part that existed when it was submitted: %s parts"
                        + " touched, %s existed", unbounded.parts(), totalPartsBeforeUnbounded)
                .isGreaterThanOrEqualTo(totalPartsBeforeUnbounded);

        // The contrast itself, compared directly rather than each measurement against a separately-queried "total
        // active parts" snapshot: this class runs every test PER_CLASS against the same shared table (see class
        // Javadoc), and that denominator drifts with whatever residue earlier tests left in OTHER partitions -
        // ClickHouse can also apply a pending mutation opportunistically during an unrelated background merge, which
        // showed up here as bounded.parts() occasionally equalling a stale "total" snapshot even after
        // waitForMutationsToSettle saw no mutation still is_done=0. Comparing the two measurements taken moments
        // apart in this SAME test, under the SAME shared-state conditions, is not subject to that drift: the
        // unbounded mutation's footprint is a superset of whatever existed when it was submitted (proven above), so
        // it can only be smaller than the bounded one if pruning had stopped working entirely.
        assertThat(bounded.parts())
                .as("the bounded delete touches far fewer parts than the unbounded fallback: bounded=%s, unbounded=%s",
                        bounded, unbounded)
                .isLessThan(unbounded.parts());
    }

    @Test
    @DisplayName("the topology setup is a no-op once the estate provides it - the path it takes post-cutover")
    void topologySetupIsANoOpOnceTheEstateProvidesIt() {
        // Today this suite installs the topology itself, so both setup steps take their INSTALL path and their
        // early returns are dead code. Once the cutover migration lands, the migrations will provide
        // `traces_local` partitioned with the Distributed `traces` over it: the EXCHANGE and the wrap are no
        // longer needed, and that early return becomes the ONLY path either step takes. Nothing would exercise it
        // until the day it becomes load-bearing, which is the wrong day to find out it was wrong.
        //
        // Re-running the setup against the topology it already installed IS that shape - `traces_local` exists and
        // `traces` is Distributed over it, which is what the migration will hand us. So this covers the second of
        // the two states: with the swap (every other test here) and without it (this one).
        // A row that already exists BEFORE the setup runs, created through the ingestion path so the check reads it
        // back the way production does. Metadata on its own cannot see data loss: a table recreated from the same
        // DDL reports the same engine_full and partition_key while being empty, so a step that rebuilt the topology
        // rather than skipping it would satisfy every metadata assertion here. The surviving row is the assertion
        // that distinguishes "skipped" from "rebuilt", and reading it through the wrapper also shows routing is
        // intact rather than merely that the wrapper exists.
        var existing = newTrace().build();
        traceResourceClient.createTrace(existing, API_KEY, WORKSPACE_NAME);
        assertThat(traceIdsOf(existing.projectName()))
                .as("the pre-existing row is readable before the setup re-runs")
                .contains(existing.id());

        // Not a tautology either: if either step failed to early-return it would THROW, not quietly repeat itself.
        // The EXCHANGE needs `traces_local_v2`, which the install renamed to `traces_pre_cutover_backup`; and the
        // wrap ends in a RENAME onto `traces_local`, which by now exists. So a non-idempotent step fails loudly.
        var tracesEngineBefore = queryOneString(TABLE_ENGINE_FULL, _ -> {
        });
        var localPartitionKeyBefore = partitionKeyOf("traces_local");

        ensurePartitionedSuccessorUnderTraces();
        ensureDistributedWrap();

        assertThat(traceIdsOf(existing.projectName()))
                .as("the row that existed before the setup is still there, and still routed through the wrapper")
                .contains(existing.id());
        assertThat(queryOneString(TABLE_ENGINE_FULL, _ -> {
        }))
                .as("re-running the setup left the Distributed wrapper untouched")
                .isEqualTo(tracesEngineBefore);
        assertThat(partitionKeyOf("traces_local"))
                .as("and left the partitioned table's key untouched")
                .isEqualTo(localPartitionKeyBefore);

        // Not just survivable - still testing what it claims. A pruned delete on the untouched topology, so a
        // no-op setup cannot quietly leave the suite asserting against something that is no longer partitioned.
        var projectId = ID_GENERATOR.generateId();
        var id = idInWeekOf(ERA_MONDAYS.getFirst());
        insertRawTrace(projectId, id);

        delete(Set.of(Pair.of(projectId, id)));

        assertThat(liveRowCount(projectId, id))
                .as("the row is still deleted after a no-op setup")
                .isEqualTo("0");
        assertThat(lastTraceDeleteSql(id))
                .as("and the delete is still pruned")
                .contains("IN PARTITION");
    }

    @Test
    @DisplayName("a request spanning two chunks prunes each chunk on its own")
    void requestSpanningTwoChunksPrunesEachChunkIndependently() {
        // The DAO chunks a request at ANALYTICS_DELETE_BATCH_SIZE and derives partitions PER CHUNK, inside the
        // concatMap - so "all-or-nothing" is a per-statement guarantee, not a per-request one. Every other test
        // here passes a handful of pairs, which is one chunk, so none of them can see that.
        //
        // The underivable id goes in the FIRST chunk and the derivable ids in the second, the only arrangement
        // that can actually discriminate. Chunks are sized [BATCH_SIZE, remainder], so the first is always full and
        // never inspectable: query_log truncates at log_queries_cut_to_length (100,000 bytes here) and a
        // 10,000-pair statement inlines to ~762 KiB, so its tail - where the predicate sits - is not recorded.
        // Only the remainder chunk is small enough to read back, so the assertion that matters has to live there.
        //
        // That makes the test bite on the refactor it exists to catch. Hoisting weeklyPartitionsFor out of the
        // lambda, so the whole request is derived once, would let the underivable id in chunk one strip pruning from
        // chunk two as well - and chunk two's predicate is exactly what is asserted below. Removing the pruning
        // outright fails the same assertion. With the arrangement reversed, both regressions passed.
        var projectId = ID_GENERATOR.generateId();
        var firstChunkRow = idInWeekOf(ERA_MONDAYS.getFirst());
        var secondChunkRow = idInWeekOf(ERA_MONDAYS.getLast());
        insertRawTrace(projectId, firstChunkRow);
        insertRawTrace(projectId, secondChunkRow);

        // Chunk one: a real row, the underivable id, and filler up to exactly ANALYTICS_DELETE_BATCH_SIZE. Filler ids
        // match no row - a delete does not need its ids to exist, and the chunk boundary is what is under test.
        var ordered = new ArrayList<UUID>();
        ordered.add(firstChunkRow);
        ordered.add(newOutOfRangeId());
        while (ordered.size() < ANALYTICS_DELETE_BATCH_SIZE) {
            ordered.add(idInWeekOf(ERA_MONDAYS.getFirst()));
        }
        // Chunk two: the remainder, all derivable, in two different weeks so the bound set is exact rather than
        // trivially a single value.
        var secondChunkCompanion = idInWeekOf(ERA_MONDAYS.get(1));
        ordered.add(secondChunkRow);
        ordered.add(secondChunkCompanion);

        var since = Instant.now();
        delete(ordered.stream().map(id -> Pair.of(projectId, id)).collect(Collectors.toCollection(
                LinkedHashSet::new)));

        assertThat(liveRowCount(projectId, firstChunkRow))
                .as("the row in the chunk that fell back to unbounded is deleted")
                .isEqualTo("0");
        assertThat(liveRowCount(projectId, secondChunkRow))
                .as("and so is the row in the chunk that pruned")
                .isEqualTo("0");

        var sqls = deleteSqlsSince(since, projectId);
        // Chunk one fell back to exactly one unbounded statement: the underivable id disables pruning for the whole
        // chunk, so it never fans out per-partition the way chunk two does below. Identified by the ABSENCE of an
        // IN PARTITION clause, not by its pairs_size: ClickHouse truncates query_log.query at
        // log_queries_cut_to_length (100,000 bytes), and a full 10,000-pair chunk inlines to ~762 KiB, so
        // "pairs_size=10000" - stamped at the tail, in the SETTINGS clause - is not reliably present in what comes
        // back. "IN PARTITION" sits right after "DELETE FROM <table>", at the very front of the statement, so its
        // absence is readable regardless of truncation.
        var chunkOneSqls = sqls.stream().filter(sql -> !sql.contains("IN PARTITION")).toList();
        assertThat(chunkOneSqls)
                .as("the full chunk fell back to exactly one unbounded statement: %s", sqls)
                .hasSize(1);

        // Chunk two: one statement per partition its two ids resolve to - the far-future row names two (its own week
        // and its legacy wrap) and the companion names one, so three statements total, each pairs_size=1. That makes
        // the test bite on the refactor it exists to catch: hoisting the derivation out of the per-chunk concatMap,
        // so the whole request is derived once, would let the underivable id in chunk one strip pruning from chunk two as
        // well - collapsing it back to one unbounded statement, which the size assertion below would catch.
        var chunkTwoSqls = sqls.stream().filter(sql -> !chunkOneSqls.contains(sql)).toList();
        assertThat(chunkTwoSqls)
                .as("the all-derivable chunk prunes even though an earlier chunk could not - one statement per"
                        + " partition: %s", chunkTwoSqls)
                .hasSize(3)
                .allSatisfy(sql -> assertThat(sql).contains("pairs_size=1"));
        assertThat(chunkTwoSqls.stream().map(TracesPartitionPruningMutationTest::boundPartitionOf))
                .as("bounded to exactly its own two weeks, plus the legacy representation of the far-future one")
                .containsExactlyInAnyOrder(partitionNameOf(ERA_MONDAYS.getLast()),
                        partitionNameOf(ERA_MONDAYS.get(1)),
                        LEGACY_WEEK_OF_2200);
    }

    @Test
    @DisplayName("an id with no derivable partition disables pruning for the batch, and the delete still lands")
    void underivableIdDisablesPruning() {
        var underivableId = newOutOfRangeId();
        // The fallback that preserves the pre-OPIK-6901 guarantee, as the original javadoc stated it: a row whose id_at
        // cannot be trusted is STILL DELETED. That is a claim about the underivable row ITSELF, so it gets a real row
        // here - seeded raw, since ingestion rejects a far-future id by design. Passing it as an id matching nothing
        // would let an implementation that quietly drops underivable ids from the batch pass, which is the very bug the
        // all-or-nothing rule exists to prevent.
        var target = newTrace().build();
        traceResourceClient.createTrace(target, API_KEY, WORKSPACE_NAME);
        var projectId = projectIdOf(target);
        insertRawTrace(projectId, underivableId);
        assertThat(liveRowCount(projectId, underivableId))
                .as("the beyond-2299 row is seeded before the delete").isEqualTo("1");

        delete(Set.of(Pair.of(projectId, target.id()), Pair.of(projectId, underivableId)));

        assertThat(liveRowCount(projectId, underivableId))
                .as("the beyond-2299 row is itself deleted, not skipped")
                .isEqualTo("0");
        assertThat(traceIdsOf(target.projectName()))
                .as("and the derivable row batched alongside it goes too")
                .doesNotContain(target.id());

        // Asserted as the absence of ANY id_at predicate, not just of this PR's expression. A regression that narrowed
        // the mutation with toMonday(id_at), an id_at range, or any other partition predicate would skip exactly the
        // rows this fallback exists to reach, and rejecting one function name would not see it. The unbounded template
        // mentions id_at nowhere at all, so that is the whole check.
        var sql = lastTraceDeleteSql(target.id());
        assertThat(sql)
                .as("the unbounded form carries no id_at predicate of any kind")
                .doesNotContain("id_at");
        assertThat(sql)
                .as("and no IN PARTITION clause: %s", sql)
                .doesNotContain("IN PARTITION");
    }
}
