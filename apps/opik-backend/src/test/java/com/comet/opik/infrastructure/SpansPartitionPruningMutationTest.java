package com.comet.opik.infrastructure;

import com.comet.opik.api.Span;
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
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanDAO;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.WeeklyPartitions;
import com.comet.opik.utils.template.TemplateUtils;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Statement;
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
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
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.FilterUtils.ANALYTICS_DELETE_BATCH_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the partition pruning of {@code SpanDAO.DELETE_BY_IDS} against the post-cutover topology, where
 * {@code spans} is the weekly partitioned successor of migration 000115 behind the {@code Distributed} wrap — the
 * spans counterpart of {@link TracesPartitionPruningMutationTest} (OPIK-8364). The derivation itself is shared and
 * covered by {@code WeeklyPartitionsTest}; what is per-DAO is everything between it and the mutation, so what this
 * suite covers is that the DAO emits one {@code IN PARTITION} statement per partition its batch resolves to, binds
 * only that partition's ids into it, and still removes exactly the rows it removed before.
 *
 * <p><b>Correctness is asserted the way production would feel it — the rows go away.</b> Nothing here re-implements
 * the partition expression; the suite's own SQL is seeds, counts and system-table reads, one text block per query with
 * every varying value a bind (the EXCHANGE/RENAME pair in {@link #ensurePartitionedSuccessorUnderSpans()} is the one
 * exception, inline beside the Javadoc explaining it). Had the predicate resolved to any partition other than the one
 * ClickHouse filed a row under, the mutation would select the wrong parts and that row would survive.
 *
 * <p>Each test pairs that with the SQL ClickHouse actually received, because rows alone cannot see pruning silently
 * stop — a delete that stopped bounding itself is still correct, just slow. The bound partitions are checked as an
 * <b>exact</b> set: a superset would keep every delete correct while handing back the whole benefit.
 *
 * <p><b>The topology is setup, never the subject.</b> After the Liquibase migrations {@code spans} is still the legacy,
 * unpartitioned table and the successor exists only as the empty {@code spans_local_v2}, so without installing it
 * these tests would prove nothing; hand-authoring a partitioned {@code spans} instead would duplicate migration 000115
 * and reintroduce the drift this suite exists to detect. Both steps are idempotent, so the suite survives the cutover
 * migration landing — asserted by {@link #topologySetupIsANoOpOnceTheEstateProvidesIt}. The wrap is applied because
 * routing to {@code spans_local} <em>and</em> pruning is the combination production runs; routing on its own is
 * {@link SpansDistributedWrapMutationTest}'s subject and is not re-asserted here.
 *
 * <p>Two internal touches: the EXCHANGE and the wrap have no public API, so they run in raw SQL; and ingestion rejects
 * a backdated or far-future {@code id} by design, so those rows are seeded raw and handed to
 * {@link SpanDAO#deleteByIds} directly. The live cascade is still exercised end to end, by
 * {@link #traceDeleteCascadePrunesAndItsDeletionBridgeRecordSurvives}. ClickHouse and ZooKeeper are dedicated and
 * non-reused because the setup destructively renames the live {@code spans} table.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpansPartitionPruningMutationTest {

    private static final String API_KEY = "apiKey-%s".formatted(UUID.randomUUID());
    private static final String WORKSPACE_NAME = "workspace-%s"
            .formatted(RandomStringUtils.secure().nextAlphanumeric(32));
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32));

    /**
     * Captures the {@code yyyyMMdd} value of the {@code IN PARTITION} clause the scoped template emits. Only ever
     * compared against a statement read back from {@code system.query_log}, never spliced into a query.
     */
    private static final Pattern IN_PARTITION_CLAUSE = Pattern.compile("IN\\s+PARTITION\\s+(\\d{8})");

    /**
     * The table's {@code PARTITION BY} expression as ClickHouse reports it — how the topology setup tells the
     * partitioned successor apart from the legacy table, and what
     * {@link #topologySetupIsANoOpOnceTheEstateProvidesIt} compares across a re-run.
     */
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
            AND name = 'spans'
            """;

    /**
     * Supplies only the four columns without a {@code DEFAULT}. {@code id_at} is {@code MATERIALIZED}, so ClickHouse
     * derives it from {@code id} exactly as it does for a row the ingestion path wrote — restating that expression
     * here would create the very drift this suite exists to detect.
     */
    private static final String INSERT_RAW_SPAN = """
            INSERT INTO spans_local (workspace_id, project_id, trace_id, id)
            VALUES (:workspace_id, :project_id, :trace_id, :id)
            """;

    /**
     * Every {@code delete_spans_by_ids} statement finished since {@code since} for {@code projectId}, in submission
     * order — one {@code deleteByIds} call emits more than one.
     * <p>
     * Scoped by project id and not by time alone: every test here shares one workspace, and {@code query_log}'s
     * buffered writes can make another test's earlier statement visible only inside this window. Each test mints its
     * own project id, which closes that gap.
     * <p>
     * Matched on {@code log_comment}, not the query text: {@code project_id} sits after the inlined id list, so a full
     * {@code ANALYTICS_DELETE_BATCH_SIZE} chunk pushes it past {@code log_queries_cut_to_length} and out of the
     * recorded {@code query}. {@code log_comment} is its own column and is never truncated.
     */
    private static final String ALL_SPAN_DELETES_SINCE = """
            SELECT query
            FROM system.query_log
            WHERE log_comment LIKE 'delete_spans_by_ids:%'
            AND type = 'QueryFinish'
            AND event_time_microseconds >= :since
            AND log_comment LIKE concat('%project_id=', :project_id, '%')
            ORDER BY event_time_microseconds
            """;

    /**
     * Scoped by the same key {@code SpanDAO.deleteByIds} matches on. A narrower oracle would answer a different
     * question: a row for the same id in another project would keep the count at {@code 1} after a successful delete.
     */
    private static final String LIVE_ROW_COUNT = """
            SELECT toString(uniqExact(id))
            FROM spans_local
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND id = :id
            """;

    /** The deletion-events bridge rows recorded for a set of span ids (OPIK-7309). */
    private static final String BRIDGE_ROW_COUNT = """
            SELECT toString(count())
            FROM deletion_events_local
            WHERE source_table = 'spans'
            AND workspace_id = :workspace_id
            AND deleted_id IN :deleted_ids
            """;

    /**
     * The part-level proof of pruning: how many {@code MutatePart} events {@code table} logged in a window, and how
     * many distinct partitions they span. {@code EXPLAIN} cannot see this — it reports what the read planner would
     * select for a {@code SELECT}, not the parts a mutation is registered against.
     * <p>
     * Windowed by time rather than correlated by {@code query_id}: a lightweight delete returns once the mutation is
     * registered, and a MutatePart event's {@code query_id} is the background merge task's.
     */
    private static final String MUTATE_PART_EVENTS_SINCE = """
            SELECT toString(count()), toString(uniqExact(partition_id))
            FROM system.part_log
            WHERE database = currentDatabase()
            AND table = :table
            AND event_type = 'MutatePart'
            AND event_time >= :since
            """;

    private static final String ACTIVE_PART_COUNT = """
            SELECT toString(count())
            FROM system.parts
            WHERE database = currentDatabase()
            AND table = :table
            AND active
            """;

    /**
     * Settle point for the {@code MutatePart} windows. Those rows are written after the submitting statement returns,
     * so on this suite's shared table a prior test's late mutation would otherwise inflate a later test's counts.
     */
    private static final String PENDING_MUTATIONS_COUNT = """
            SELECT toString(count())
            FROM system.mutations
            WHERE database = currentDatabase()
            AND table = :table
            AND NOT is_done
            """;

    /**
     * The spans wrap, mirroring {@code SpansDistributedWrapMutationTest.applyDistributedWrap}. The database name is a
     * fragment — an identifier inside a function argument, not a bindable value — so it goes through
     * {@link TemplateUtils#newST}. The {@code {cluster}} macros are ClickHouse's own and pass through untouched.
     */
    private static final String CREATE_DISTRIBUTED_WRAPPER = """
            CREATE TABLE spans_dist ON CLUSTER '{cluster}' AS spans
            ENGINE = Distributed('{cluster}', '<database>', 'spans_local', sipHash64(project_id))
            """;

    /**
     * The weekly partitions this suite works in, each named by its Monday — which is also the partition name, since
     * the key is that Monday as {@code yyyyMMdd}. Fixed rather than {@code now}-derived so the partition math cannot
     * drift across a week boundary mid-suite, and ids are minted mid-week ({@link #idInWeekOf}) so the assertions
     * exercise the map back to Monday rather than identity.
     * <p>
     * The three eras are load-bearing, not variety. A recent-only batch cannot tell the {@code Date32} expression
     * migration 000115 installs apart from the {@code toMonday} it was written to escape, since the two agree across
     * the ordinary calendar; the far-future era is where they diverge, and is also the only one whose two
     * {@code id_at} representations differ.
     */
    private static final List<LocalDate> ERA_MONDAYS = List.of(
            LocalDate.of(1996, 2, 5),
            LocalDate.of(2025, 3, 3),
            LocalDate.of(2199, 12, 30));

    /**
     * The second partition the far-future era resolves to: the week legacy {@code spans} would file that id under,
     * since its 32-bit {@code DateTime} {@code id_at} (migration 000105) holds {@code epochSecond % 2^32}.
     * {@link WeeklyPartitions} names it alongside the honest week so one rendered statement is correct on both sides
     * of the EXCHANGE, so every exact-set assertion here has to expect it. Stated literally rather than derived
     * through {@code WeeklyPartitions}, since the asymmetry is the behaviour under test.
     */
    private static final long LEGACY_WEEK_OF_FAR_FUTURE_ERA = 20631119L;

    /**
     * Drops {@code query_log}/{@code part_log} to a 200 ms flush interval, so the readers below can poll instead of
     * forcing a {@code SYSTEM FLUSH LOGS} that races the rows it is meant to reveal.
     */
    private static final String FAST_LOG_FLUSH_CONFIG = "clickhouse-fast-log-flush.xml";

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    /** Redis and MySQL are only read, so the shared containers are fine; see the class Javadoc for the other two. */
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

    /**
     * Runs the topology setup and this suite's own reads and seeds straight against the container: it has to be
     * app-independent, since the topology must be installed before the app boots against it. Never used for anything
     * the DAO executes — it carries none of the production {@code queryParameters} — and never needs to be, since the
     * DAO opens its own connection.
     */
    private final TransactionTemplateAsync template;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer)
                .join();
        wireMock = WireMockUtils.startWireMock();
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        template = TransactionTemplateAsync.create(ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(clickHouseContainer, DATABASE_NAME)
                .build());
        ensurePartitionedSuccessorUnderSpans();
        ensureDistributedWrap();
    }

    /**
     * Declared after the instance initialiser above on purpose: field initialisers run in textual order, so the
     * topology is installed before the app boots against it.
     */
    @RegisterApp
    private final TestDropwizardAppExtension app = newApp();

    /**
     * The app, configured as production runs post-cutover. {@code spanColumnsNonNullable} is what tells the DAO both
     * that the sentinel columns are non-nullable and that the mutation target is weekly-partitioned, so without it
     * every pruning assertion here would pass vacuously against the unbounded fallback;
     * {@code spansDistributedWrapEnabled} is what points its mutations at {@code spans_local}. Deletion-events capture
     * is on so {@link #traceDeleteCascadePrunesAndItsDeletionBridgeRecordSurvives} exercises the bridge the cutover
     * depends on rather than a disabled no-op.
     */
    private TestDropwizardAppExtension newApp() {
        return TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                                clickHouseContainer, DATABASE_NAME))
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(List.of(
                                new CustomConfig("databaseAnalyticsDataModel.spanColumnsNonNullable", "true"),
                                new CustomConfig("databaseAnalyticsDataModel.spansDistributedWrapEnabled", "true"),
                                new CustomConfig("databaseAnalyticsDataModel.spanDeletionEventsCaptureEnabled",
                                        "true")))
                        .build());
    }

    private SpanResourceClient spanResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanDAO spanDAO;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, @Jit SpanDAO spanDAO) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.spanDAO = spanDAO;
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    @Test
    void deleteClearsEveryEraAndBindsExactlyThosePartitions() {
        // "Every row is gone" IS the agreement between migration 000115's PARTITION BY as installed, the DAO's
        // predicate and WeeklyPartitions: a predicate naming any other partition would select the wrong parts and
        // leave the row behind. Seeded raw, since the ingestion window is 24h and no endpoint can create these rows.
        var projectId = ID_GENERATOR.generateId();
        var ids = ERA_MONDAYS.stream().map(this::idInWeekOf).toList();
        ids.forEach(id -> insertRawSpan(projectId, id));
        assertThat(ids.stream().map(id -> liveRowCount(projectId, id)))
                .as("every era is seeded").containsOnly("1");

        var since = serverNow();
        delete(Set.copyOf(ids), projectId);

        assertThat(ids.stream().map(id -> liveRowCount(projectId, id)))
                .as("every era's row is gone, so the predicate named the partition each was actually filed under")
                .containsOnly("0");

        // One statement per partition the batch resolves to, not one statement carrying all four values: the two eras
        // inside the 32-bit range name one week each; the far-future id names two (its own week AND its legacy wrap),
        // so it is the id bound into two DIFFERENT statements rather than the batch widening to a fourth id. Four
        // statements, each ids_size=1 - still an exact set of partitions, and still not a range across two centuries.
        var sqls = deleteSqlsSince(since, projectId);
        assertThat(sqls)
                .as("one statement per partition the batch resolves to: %s", sqls)
                .hasSize(4)
                .allSatisfy(sql -> assertThat(sql).contains("ids_size=1"));
        assertThat(sqls.stream().map(this::boundPartitionOf))
                .as("exactly the partitions the batch resolves to, not a range across two centuries")
                .containsExactlyInAnyOrder(
                        partitionNameOf(ERA_MONDAYS.get(0)),
                        partitionNameOf(ERA_MONDAYS.get(1)),
                        partitionNameOf(ERA_MONDAYS.get(2)),
                        LEGACY_WEEK_OF_FAR_FUTURE_ERA);
    }

    @Test
    void scopedDeleteRemovesOnlyTheIdsItNames() {
        // IN PARTITION widens what a mistake costs: a statement that kept its partition clause but lost its id
        // predicate would delete a whole week of a project's spans, and every other test here would still pass,
        // because each seeds only rows it then deletes. These bystanders are the rows that have to survive.
        var week = ERA_MONDAYS.get(1);
        var projectId = ID_GENERATOR.generateId();
        var target = idInWeekOf(week);
        var sameProjectBystander = idInWeekOf(week);
        var otherProjectId = ID_GENERATOR.generateId();
        var otherProjectBystander = idInWeekOf(week);
        insertRawSpan(projectId, target);
        insertRawSpan(projectId, sameProjectBystander);
        insertRawSpan(otherProjectId, otherProjectBystander);

        delete(Set.of(target), projectId);

        assertThat(liveRowCount(projectId, target))
                .as("the named row is gone").isEqualTo("0");
        assertThat(liveRowCount(projectId, sameProjectBystander))
                .as("the row sharing its partition and its project survives").isEqualTo("1");
        assertThat(liveRowCount(otherProjectId, otherProjectBystander))
                .as("and so does the row sharing its partition in another project").isEqualTo("1");
    }

    @Test
    void idsSharingAPartitionAreCarriedByOneStatementNotOnePerId() {
        // Every other test here deletes one id per week, so ids_size=1 throughout - which a regression emitting one
        // statement per ID rather than per partition would satisfy just as well. Grouping is the point, so it needs a
        // case where the two differ. The far-future era is excluded: its id names two weeks and would blur the counts.
        var projectId = ID_GENERATOR.generateId();
        var weeks = List.of(ERA_MONDAYS.get(0), ERA_MONDAYS.get(1));
        var idsByWeek = weeks.stream().collect(Collectors.toUnmodifiableMap(week -> week,
                week -> IntStream.range(0, 3).mapToObj(_ -> idInWeekOf(week)).toList()));
        idsByWeek.values().stream().flatMap(List::stream).forEach(id -> insertRawSpan(projectId, id));

        var since = serverNow();
        delete(idsByWeek.values().stream().flatMap(List::stream).collect(Collectors.toUnmodifiableSet()), projectId);

        assertThat(idsByWeek.values().stream().flatMap(List::stream).map(id -> liveRowCount(projectId, id)))
                .as("every row in both weeks is gone").containsOnly("0");

        var sqls = deleteSqlsSince(since, projectId);
        assertThat(sqls)
                .as("one statement per week, not one per id - six ids across two weeks: %s", sqls)
                .hasSize(2)
                .allSatisfy(sql -> assertThat(sql).contains("ids_size=3"));

        // ids_size comes from the log comment, which the DAO stamps from the same list it means to bind - so it
        // proves the grouping decided three, not that those three reached the statement. The ids themselves are
        // inlined in the query text, so asserting them is what pins the partition-to-ids mapping: a refactor that
        // bound every id into every statement, or one week's ids into the other's statement, fails here and nowhere
        // else.
        var sqlByWeek = sqls.stream().collect(Collectors.toUnmodifiableMap(this::boundPartitionOf, sql -> sql));
        assertThat(sqlByWeek.keySet())
                .as("each statement is scoped to its own week")
                .containsExactlyInAnyOrder(partitionNameOf(weeks.get(0)), partitionNameOf(weeks.get(1)));
        weeks.forEach(week -> {
            var other = weeks.get(1 - weeks.indexOf(week));
            assertThat(sqlByWeek.get(partitionNameOf(week)))
                    .as("the statement for %s binds that week's ids, and only those", week)
                    .contains(idsOf(idsByWeek.get(week)))
                    .doesNotContain(idsOf(idsByWeek.get(other)));
        });
    }

    @Test
    void underivableIdFallsBackToOneUnboundedStatementAndStillDeletesItsRow() {
        // The fallback's guarantee: a row whose id_at cannot be trusted is STILL DELETED. That is a claim about the
        // underivable row ITSELF, so it gets a real row - passing an id matching nothing would let an implementation
        // that quietly drops underivable ids pass, which is the bug the all-or-nothing rule exists to prevent.
        var projectId = ID_GENERATOR.generateId();
        var underivableId = newOutOfRangeId();
        var derivableId = idInWeekOf(ERA_MONDAYS.get(1));
        insertRawSpan(projectId, underivableId);
        insertRawSpan(projectId, derivableId);
        assertThat(liveRowCount(projectId, underivableId))
                .as("the out-of-range row is seeded before the delete").isEqualTo("1");

        var since = serverNow();
        delete(Set.of(underivableId, derivableId), projectId);

        assertThat(liveRowCount(projectId, underivableId))
                .as("the out-of-range row is itself deleted, not skipped")
                .isEqualTo("0");
        assertThat(liveRowCount(projectId, derivableId))
                .as("and the derivable row batched alongside it goes too")
                .isEqualTo("0");

        // Asserted as the absence of ANY id_at predicate, not just of IN PARTITION: a regression narrowing the
        // mutation with a toMonday(id_at) or an id_at range would skip exactly the rows this fallback exists to reach.
        var sqls = deleteSqlsSince(since, projectId);
        assertThat(sqls)
                .as("the chunk fell back to exactly one unbounded statement: %s", sqls)
                .hasSize(1)
                .allSatisfy(sql -> assertThat(sql).contains("ids_size=2"));
        assertThat(sqls.getFirst())
                .as("the unbounded form carries no IN PARTITION clause and no id_at predicate of any kind")
                .doesNotContain("IN PARTITION")
                .doesNotContain("id_at");
    }

    @Test
    void requestSpanningTwoChunksPrunesEachChunkIndependently() {
        // Partitions are derived PER CHUNK, so "all-or-nothing" is a per-statement guarantee, not a per-request one;
        // every other test here passes one chunk and cannot see that. The underivable id goes in the FIRST chunk and
        // the derivable ids in the second, which is what makes the test bite: hoisting the derivation out of the
        // per-chunk step would let chunk one strip pruning from chunk two, and chunk two is what is asserted below.
        // It is also the only workable arrangement, since chunks are sized [BATCH_SIZE, remainder] and only the
        // remainder is small enough to read back in full.
        var projectId = ID_GENERATOR.generateId();
        var firstChunkRow = idInWeekOf(ERA_MONDAYS.getFirst());
        var secondChunkRow = idInWeekOf(ERA_MONDAYS.getLast());
        insertRawSpan(projectId, firstChunkRow);
        insertRawSpan(projectId, secondChunkRow);

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

        var since = serverNow();
        delete(new LinkedHashSet<>(ordered), projectId);

        assertThat(liveRowCount(projectId, firstChunkRow))
                .as("the row in the chunk that fell back to unbounded is deleted")
                .isEqualTo("0");
        assertThat(liveRowCount(projectId, secondChunkRow))
                .as("and so is the row in the chunk that pruned")
                .isEqualTo("0");

        var sqls = deleteSqlsSince(since, projectId);
        // Identified by the ABSENCE of an IN PARTITION clause rather than by ids_size: the clause sits at the very
        // front of the statement, so it is readable regardless of where query_log truncated the text.
        var chunkOneSqls = sqls.stream().filter(sql -> !sql.contains("IN PARTITION")).toList();
        assertThat(chunkOneSqls)
                .as("the full chunk fell back to exactly one unbounded statement: %s", sqls)
                .hasSize(1);

        // Chunk two: the far-future row names two partitions and the companion one, so three statements.
        var chunkTwoSqls = sqls.stream().filter(sql -> !chunkOneSqls.contains(sql)).toList();
        assertThat(chunkTwoSqls)
                .as("the all-derivable chunk prunes even though an earlier chunk could not - one statement per"
                        + " partition: %s", chunkTwoSqls)
                .hasSize(3)
                .allSatisfy(sql -> assertThat(sql).contains("ids_size=1"));
        assertThat(chunkTwoSqls.stream().map(this::boundPartitionOf))
                .as("bounded to exactly its own two weeks, plus the legacy representation of the far-future one")
                .containsExactlyInAnyOrder(partitionNameOf(ERA_MONDAYS.getLast()),
                        partitionNameOf(ERA_MONDAYS.get(1)),
                        LEGACY_WEEK_OF_FAR_FUTURE_ERA);
    }

    @Test
    void argumentsAreRejectedBeforeAnyStatementIsBuilt() {
        // Without the null precondition a null id reads as "underivable", so a caller's bug would surface as the slow
        // delete this change exists to prevent, and only then as an NPE while stringifying the binds. An empty set
        // would reach ClickHouse as `id IN []`, deleting nothing while registering a mutation.
        var projectId = ID_GENERATOR.generateId();
        var withNull = new HashSet<UUID>();
        withNull.add(ID_GENERATOR.generateId());
        withNull.add(null);

        assertThatThrownBy(() -> delete(withNull, projectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain null ids");
        assertThatThrownBy(() -> delete(Set.of(), projectId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void traceDeleteCascadePrunesAndItsDeletionBridgeRecordSurvives() {
        // The only path production takes: spans have no standalone delete endpoint. It also covers the OPIK-8141
        // failure mode from the cascade side - captureDeletions is best-effort and sits immediately before
        // deleteByIds, so a delete now emitting several statements must still leave the bridge record standing.
        var trace = newTrace().build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        var projectId = projectIdOf(trace);

        // Two spans on the trace, in different eras, so the cascade fans out across three partitions rather than one.
        // That is the shape this ticket introduces and the reason the bridge needs re-verifying: a single-statement
        // cascade would prove nothing new about a delete that now emits several.
        //
        // Both seeded raw rather than through the ingestion path, which would mint ids at "now" - a week the test
        // cannot name, and so one whose expected partition could only be derived through the same helper the DAO
        // uses. Naming the weeks keeps every expected value below independent of the derivation under test. The
        // cascade reaches these rows the same way either way: it resolves its span ids by trace_id.
        var recentEraSpanId = idInWeekOf(ERA_MONDAYS.get(1));
        var farFutureSpanId = idInWeekOf(ERA_MONDAYS.getLast());
        insertRawSpan(projectId, trace.id(), recentEraSpanId);
        insertRawSpan(projectId, trace.id(), farFutureSpanId);

        var since = serverNow();
        traceResourceClient.deleteTrace(trace.id(), WORKSPACE_NAME, API_KEY);

        // The cascade runs on the AsyncEventBus after the trace delete returns, so its outcome is polled.
        Awaitility.await()
                .alias("the cascade removed both spans")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(List.of(liveRowCount(projectId, recentEraSpanId),
                        liveRowCount(projectId, farFutureSpanId))).containsOnly("0"));

        assertThat(bridgeRowCount(Set.of(recentEraSpanId, farFutureSpanId)))
                .as("both bridge records are still there after the multi-statement delete that followed them")
                .isEqualTo("2");

        // The cascade is the high-volume path, so this is what says production's trace deletes are the ones pruned.
        var sqls = deleteSqlsSince(since, projectId);
        assertThat(sqls.stream().map(this::boundPartitionOf))
                .as("the cascade pruned to exactly the partitions its two spans resolve to: %s", sqls)
                .containsExactlyInAnyOrder(partitionNameOf(ERA_MONDAYS.get(1)),
                        partitionNameOf(ERA_MONDAYS.getLast()),
                        LEGACY_WEEK_OF_FAR_FUTURE_ERA);
    }

    @Test
    void boundedDeleteTouchesFarFewerPartsThanTheUnboundedFallback() {
        // Correctness and pruning are different claims, and this is the only test that makes the second one: deletes
        // were already correct before this change, and what it buys is parts touched. Asked of system.part_log's
        // MutatePart events, the layer a mutation is registered against, rather than of EXPLAIN.
        var table = "spans_local";
        var projectId = ID_GENERATOR.generateId();
        var ids = ERA_MONDAYS.stream().map(this::idInWeekOf).toList();
        ids.forEach(id -> insertRawSpan(projectId, id));

        // Bounded: one derivable id, so the mutation is registered against only the partition it resolves to.
        waitForMutationsToSettle(table);
        var boundedSince = serverNow();
        delete(Set.of(ids.getFirst()), projectId);
        // Settle AFTER the delete too, before snapshotting: part_log's own settling waits for rows to become visible,
        // not for the mutation to finish producing them, so without this the snapshot can be taken mid-mutation and
        // under-count. `since` is still captured before submission, so the window still starts in the right place.
        waitForMutationsToSettle(table);
        var bounded = mutatePartActivitySince(table, boundedSince);

        // Exactly-1 is the true behaviour, but ClickHouse applies a sibling test's pending mutation opportunistically
        // inside this window, which waitForMutationsToSettle cannot prevent. So the assertion that discriminates is
        // the comparison against the unbounded arm below, not an absolute count the shared table makes unstable.
        assertThat(bounded.partitions())
                .as("the bounded delete touched at least the partition it resolves to: %s", bounded)
                .isGreaterThanOrEqualTo(1);

        // Unbounded: an underivable id in the batch, so no IN PARTITION at all and the mutation visits every part.
        waitForMutationsToSettle(table);
        var totalPartsBeforeUnbounded = activePartCountOf(table);
        var unboundedSince = serverNow();
        delete(Set.of(ids.get(1), newOutOfRangeId()), projectId);
        waitForMutationsToSettle(table);
        var unbounded = mutatePartActivitySince(table, unboundedSince);

        assertThat(unbounded.parts())
                .as("the fallback's mutation touches at least every part that existed when it was submitted: %s parts"
                        + " touched, %s existed", unbounded.parts(), totalPartsBeforeUnbounded)
                .isGreaterThanOrEqualTo(totalPartsBeforeUnbounded);

        // The contrast itself, compared directly rather than each measurement against a separately-queried "total
        // active parts" snapshot, which drifts with whatever residue earlier tests left in other partitions. These two
        // were taken moments apart under the same conditions, and the unbounded footprint is a superset of what
        // existed when it was submitted (above), so it can only be the smaller of the two if pruning stopped entirely.
        assertThat(bounded.parts())
                .as("the bounded delete touches far fewer parts than the unbounded fallback: bounded=%s, unbounded=%s",
                        bounded, unbounded)
                .isLessThan(unbounded.parts());
    }

    @Test
    void topologySetupIsANoOpOnceTheEstateProvidesIt() {
        // Today both setup steps take their INSTALL path and their early returns are dead code. Once the cutover
        // migration provides the topology, that early return becomes the ONLY path either takes - and nothing would
        // exercise it until the day it became load-bearing, which is the wrong day to find out it was wrong.
        //
        // The pre-existing row is what distinguishes "skipped" from "rebuilt": a table recreated from the same DDL
        // reports the same engine_full and partition_key while being empty, so metadata alone cannot see data loss.
        var trace = newTrace().build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        var existing = newSpan(trace.projectName(), trace.id()).build();
        spanResourceClient.createSpan(existing, API_KEY, WORKSPACE_NAME);
        assertThat(spanIdsOf(trace))
                .as("the pre-existing row is readable before the setup re-runs")
                .contains(existing.id());

        // Not a tautology: a step that failed to early-return would THROW, not quietly repeat itself - the EXCHANGE
        // needs `spans_local_v2`, which the install renamed away, and the wrap ends in a RENAME onto an existing name.
        var spansEngineBefore = queryOneString(TABLE_ENGINE_FULL, _ -> {
        });
        var localPartitionKeyBefore = partitionKeyOf("spans_local");

        ensurePartitionedSuccessorUnderSpans();
        ensureDistributedWrap();

        assertThat(spanIdsOf(trace))
                .as("the row that existed before the setup is still there, and still routed through the wrapper")
                .contains(existing.id());
        assertThat(queryOneString(TABLE_ENGINE_FULL, _ -> {
        }))
                .as("re-running the setup left the Distributed wrapper untouched")
                .isEqualTo(spansEngineBefore);
        assertThat(partitionKeyOf("spans_local"))
                .as("and left the partitioned table's key untouched")
                .isEqualTo(localPartitionKeyBefore);

        // Not just survivable - still testing what it claims, so a no-op setup cannot quietly leave the suite
        // asserting against something that is no longer partitioned.
        var projectId = ID_GENERATOR.generateId();
        var id = idInWeekOf(ERA_MONDAYS.getFirst());
        insertRawSpan(projectId, id);

        var since = serverNow();
        delete(Set.of(id), projectId);

        assertThat(liveRowCount(projectId, id))
                .as("the row is still deleted after a no-op setup")
                .isEqualTo("0");
        var sqls = deleteSqlsSince(since, projectId);
        assertThat(sqls).as("one statement, as the single-week batch resolves to: %s", sqls).hasSize(1);
        assertThat(sqls.getFirst()).as("and the delete is still pruned").contains("IN PARTITION");
    }

    /**
     * Setup: puts the partitioned successor under the name the DAO deletes from. Idempotent because the estate will
     * change — once the cutover migration lands {@code spans} <em>is</em> the successor and {@code spans_local_v2} is
     * gone, at which point this is a no-op rather than an {@code EXCHANGE} against a table that no longer exists. If
     * neither state holds it says so, rather than surfacing as a bare "table not found".
     * <p>
     * Shaped like the traces cutover's exchange block: swap the successor under {@code spans}, then park the original
     * as {@code spans_pre_cutover_backup} — the name {@code MutationSql.SPANS} already knows.
     */
    private void ensurePartitionedSuccessorUnderSpans() {
        if (tableExists("spans_local") || partitionKeyOf("spans").contains("id_at")) {
            return; // Already installed, or the cutover migration has landed.
        }
        assertThat(tableExists("spans_local_v2"))
                .as("neither `spans_local` nor a partitioned `spans` nor `spans_local_v2` is present (partition key of"
                        + " `spans`: '%s') - this suite needs one of those states to install the successor from",
                        partitionKeyOf("spans"))
                .isTrue();
        execute("EXCHANGE TABLES spans AND spans_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });
        execute("RENAME TABLE spans_local_v2 TO spans_pre_cutover_backup ON CLUSTER '{cluster}'", _ -> {
        });
    }

    /**
     * Setup: applies the sharding-readiness wrap, so the DAO's mutations reach the data through the configuration
     * switch that governs them in production rather than through a table this suite renamed under it. Mirrors
     * {@code SpansDistributedWrapMutationTest.applyDistributedWrap} — build the wrapper under a temp name, then one
     * atomic multi-target {@code RENAME}, so {@code spans} is never absent. Re-entrant: it returns early when the wrap
     * is applied, and clears a wrapper stranded by an interrupted run before rebuilding it.
     */
    private void ensureDistributedWrap() {
        // Checked on the definition, not the engine name: a wrapper over another database or local table would
        // otherwise block the rebuild and silently route rows elsewhere. Matched on the two parts that decide where
        // rows go, not the whole string, which ClickHouse re-prints.
        var engineFull = Optional.ofNullable(queryOneString(TABLE_ENGINE_FULL, _ -> {
        })).orElse("");
        if (engineFull.startsWith("Distributed")) {
            assertThat(engineFull)
                    .as("`spans` is already Distributed but not over this database's spans_local: %s", engineFull)
                    .contains("'%s'".formatted(DATABASE_NAME))
                    .contains("'spans_local'");
            return;
        }
        // Clear a wrapper stranded by a run that died between the CREATE and the RENAME. It holds no data, and
        // without this the CREATE below fails on a duplicate name and buries the real state.
        execute("DROP TABLE IF EXISTS spans_dist ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute(TemplateUtils.newST(CREATE_DISTRIBUTED_WRAPPER)
                .add("database", DATABASE_NAME)
                .render(), _ -> {
                });
        execute("""
                RENAME TABLE
                    spans TO spans_local,
                    spans_dist TO spans
                    ON CLUSTER '{cluster}'
                """, _ -> {
        });
    }

    /**
     * Invokes the DAO under a workspace/user context, as {@code SpanService} does for the live cascade.
     * <p>
     * Callers read row counts straight afterwards without polling, and that is deterministic rather than lucky:
     * {@code lightweight_deletes_sync} defaults to {@code 2}, so the statement does not return until its mutation has
     * been applied. {@link #traceDeleteCascadePrunesAndItsDeletionBridgeRecordSurvives} polls for an unrelated reason
     * - its trace delete returns before the {@code AsyncEventBus} listener that runs the cascade.
     */
    private void delete(Set<UUID> spanIds, UUID projectId) {
        spanDAO.deleteByIds(spanIds, projectId)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER))
                .block();
    }

    /** A UUIDv7 in the given week, minted mid-week so the partition assertion exercises the map back to Monday. */
    private UUID idInWeekOf(LocalDate monday) {
        return ID_GENERATOR.generateId(monday.plusDays(2).atTime(12, 0).toInstant(ZoneOffset.UTC));
    }

    /**
     * A UUIDv7 past the first instant {@code DateTime64} can represent, so its {@code id_at} saturates to the ceiling
     * and the honest week is not the partition the row lands in — the only underivable cause that occurs in real data,
     * a non-v7 id being rejected at ingestion. Distinct per call, since these tests share one table.
     */
    private UUID newOutOfRangeId() {
        return ID_GENERATOR.getTimeOrderedEpoch(
                LocalDate.of(2300, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli());
    }

    /**
     * The partition name for a week: its Monday as {@code yyyyMMdd}. Formats a Monday the test already names, rather
     * than re-deriving the Monday of an arbitrary date, which is the part under test.
     */
    private long partitionNameOf(LocalDate monday) {
        return monday.getYear() * 10000L + monday.getMonthValue() * 100L + monday.getDayOfMonth();
    }

    /** The ids as they appear inlined in a statement's {@code id IN [...]} list. */
    private String[] idsOf(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toArray(String[]::new);
    }

    /** The partition an {@code IN PARTITION} statement names — exactly one per statement, or none. */
    private long boundPartitionOf(String sql) {
        var clause = IN_PARTITION_CLAUSE.matcher(sql);
        assertThat(clause.find())
                .as("the delete SQL carries an IN PARTITION clause:%n%s", sql)
                .isTrue();
        return Long.parseLong(clause.group(1));
    }

    /** Polled until the set stops growing rather than flushed — see {@link #FAST_LOG_FLUSH_CONFIG}. */
    private List<String> deleteSqlsSince(Instant since, UUID projectId) {
        var previous = new AtomicReference<List<String>>();
        return Awaitility.await()
                .alias("query_log settles for project %s".formatted(projectId))
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .until(() -> {
                    var sample = readDeleteSqlsSince(since, projectId);
                    return !sample.isEmpty() && sample.equals(previous.getAndSet(sample)) ? sample : null;
                }, Objects::nonNull);
    }

    private List<String> readDeleteSqlsSince(Instant since, UUID projectId) {
        return template.stream(connection -> {
            var statement = connection.createStatement(ALL_SPAN_DELETES_SINCE)
                    .bind("since", since.atOffset(ZoneOffset.UTC).toLocalDateTime())
                    .bind("project_id", projectId.toString());
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, _) -> row.get("query", String.class)));
        }).collectList().block();
    }

    /** {@code "1"} while a live (non-lightweight-deleted) row exists for the id, {@code "0"} once it is gone. */
    private String liveRowCount(UUID projectId, UUID id) {
        return queryOneString(LIVE_ROW_COUNT, statement -> statement
                .bind("workspace_id", WORKSPACE_ID)
                .bind("project_id", projectId.toString())
                .bind("id", id.toString()));
    }

    private String bridgeRowCount(Set<UUID> spanIds) {
        return queryOneString(BRIDGE_ROW_COUNT, statement -> statement
                .bind("workspace_id", WORKSPACE_ID)
                .bind("deleted_ids", spanIds.stream().map(UUID::toString).toArray(String[]::new)));
    }

    /**
     * Read until two consecutive samples agree, rather than forcing a flush: {@code system.part_log} is buffered and
     * there is no single row to wait for, only a count that grows until the last MutatePart event lands.
     */
    private MutatePartActivity mutatePartActivitySince(String table, Instant since) {
        var previous = new AtomicReference<MutatePartActivity>();
        return Awaitility.await()
                .alias("part_log settles for %s".formatted(table))
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
                    .flatMap(result -> Mono.from(result.map((row, _) -> MutatePartActivity.builder()
                            .parts(Integer.parseInt(row.get(0, String.class)))
                            .partitions(Integer.parseInt(row.get(1, String.class)))
                            .build())));
        }).block();
    }

    /**
     * {@code now()} as ClickHouse sees it, and the only clock every window bound here may come from: the container's
     * can differ from the JVM's by enough that a JVM-derived bound either reaches back and sweeps in earlier work on
     * this shared table, or starts after the statement it is meant to catch. Truncated to the second, so it can only
     * widen a window — which the per-test project id already makes safe.
     */
    private Instant serverNow() {
        return Instant.parse(queryOneString("SELECT formatDateTime(now(), '%Y-%m-%dT%H:%i:%SZ')", _ -> {
        }));
    }

    private int activePartCountOf(String table) {
        return Integer.parseInt(queryOneString(ACTIVE_PART_COUNT, statement -> statement.bind("table", table)));
    }

    /** Blocks until {@code table} has no in-flight mutation. See {@link #PENDING_MUTATIONS_COUNT}. */
    private void waitForMutationsToSettle(String table) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> "0".equals(queryOneString(PENDING_MUTATIONS_COUNT,
                        statement -> statement.bind("table", table))));
    }

    private boolean tableExists(String table) {
        return "1".equals(queryOneString(TABLE_COUNT, statement -> statement.bind("table", table)));
    }

    /**
     * The table's partition-key expression, or {@code ""} when there is no such table — never {@code null}. A missing
     * row is legitimate here (a half-applied wrap can leave {@code spans} renamed away) and the setup guard has to
     * report it rather than die dereferencing it, which would bury the diagnostic.
     */
    private String partitionKeyOf(String table) {
        return Optional
                .ofNullable(queryOneString(PARTITION_KEY_OF_TABLE, statement -> statement.bind("table", table)))
                .orElse("");
    }

    /**
     * A trace with every trace-table column populated. Only the span-derived aggregates podam would otherwise
     * fabricate are nulled, since they are not columns of the {@code traces} table.
     */
    private Trace.TraceBuilder newTrace() {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null);
    }

    /**
     * A span in the given project and trace. {@code feedbackScores} is derived rather than a column; {@code usage} is
     * nulled because podam fabricates counts past {@code Integer}, which the API model cannot read back.
     */
    private Span.SpanBuilder newSpan(String projectName, UUID traceId) {
        return factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .traceId(traceId)
                .usage(null)
                .feedbackScores(null);
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

    private List<UUID> spanIdsOf(Trace trace) {
        return spanResourceClient
                .getByTraceIdAndProject(trace.id(), trace.projectName(), WORKSPACE_NAME, API_KEY)
                .content().stream()
                .map(Span::id)
                .toList();
    }

    /**
     * Seeds one row in the caller's project, under a {@code trace_id} of its own. Most tests here never filter on
     * {@code trace_id}, so reusing the span id keeps the seed to one parameter; the cascade test needs a real one.
     */
    private void insertRawSpan(UUID projectId, UUID id) {
        insertRawSpan(projectId, id, id);
    }

    /**
     * Seeds one row under a given trace, which is what makes it visible to the cascade's {@code trace_id} lookup. Raw
     * SQL because ingestion rejects a backdated or far-future {@code id} by design.
     */
    private void insertRawSpan(UUID projectId, UUID traceId, UUID id) {
        execute(INSERT_RAW_SPAN,
                statement -> statement
                        .bind("workspace_id", WORKSPACE_ID)
                        .bind("project_id", projectId.toString())
                        .bind("trace_id", traceId.toString())
                        .bind("id", id.toString()));
    }

    /** First column of the first row, as a string. Every read in this suite is a single scalar. */
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

    /**
     * {@code MutatePart} events observed in a window: how many parts were touched, and how many partitions. Built
     * through the builder rather than the canonical constructor — both components are {@code int}, so a positional
     * call can transpose them silently, and which is what this suite concludes from.
     */
    @Builder(toBuilder = true)
    private record MutatePartActivity(int parts, int partitions) {
    }
}
