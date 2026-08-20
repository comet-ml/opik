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
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.WeeklyPartitions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 * <p><b>Everything is asserted through the DAO's own delete.</b> This suite writes no query that re-implements or
 * re-evaluates the partition expression, and it asserts nothing about the EXCHANGE — the EXCHANGE is only setup (see
 * below). Its own SQL is three statements, all plain binds: seed a row, count a row, read a statement back from
 * {@code system.query_log}. Correctness is established the way production would feel it — <b>the rows go away</b>. If
 * the DAO's predicate resolved to any partition other than the one ClickHouse filed a row under, the mutation would
 * select the wrong parts and that row would survive, so
 * {@link #deleteClearsEveryEraAndBindsExactlyThosePartitions} passing <em>is</em> the agreement between the migration's
 * {@code PARTITION BY} as installed, the DAO's predicate, and {@link WeeklyPartitions#of}.
 *
 * <p>Each test then pairs that with the SQL ClickHouse actually received, because rows alone cannot see pruning
 * silently stop — a delete that stopped bounding itself is still correct, just slow, and that is the regression this
 * change exists to prevent. Read back by {@code log_comment} plus the test's own trace id, and checked as an
 * <b>exact</b> bound partition set: a superset would keep every delete correct while handing back the whole benefit.
 *
 * <p>The eras in {@link #deleteClearsEveryEraAndBindsExactlyThosePartitions} are load-bearing, not variety.
 * {@code toMonday} agrees with the {@code Date32} expression across the ordinary calendar and diverges only far-future
 * or at the epoch, so a recent-only batch would accept the very expression migration 000114 was written to escape. The
 * 2200 row also covers the {@code DateTime64} half of what the flag asserts: against a 32-bit {@code id_at} it would be
 * stored under a wrapped recent timestamp, the derived partition would not match, and it would survive.
 *
 * <p>Two internal touches, on the pattern of {@code TracesDistributedWrapMutationTest}: the EXCHANGE has no public API,
 * so {@link #beforeAll} runs it in raw SQL identical to the swap block of {@code 000003_exchange_and_wrap.sql}; and the
 * ingestion path rejects a non-v7, backdated or far-future {@code id} by design ({@code IdGenerator.validateId}), so
 * those rows are seeded raw and those batches are handed to {@link TraceDAO#delete} directly — the only way to reach
 * that arm.
 *
 * <p><b>Why the EXCHANGE is here at all — it is setup, never the subject.</b> Nothing asserts anything about it. It is
 * required because the DAO names its target table: after the Liquibase migrations the live {@code traces} is still the
 * <em>legacy</em> table — no {@code PARTITION BY} at all and a 32-bit {@code DateTime} {@code id_at} — while the
 * partitioned successor exists only as the empty {@code traces_local_v2}, which no DAO query can reach. Two statements
 * copied from the cutover put the successor under the name the DAO deletes from. Without them these tests would run
 * against the one table where this predicate must never be emitted, and would pass while proving nothing: the predicate
 * is harmless against an unpartitioned table for recent ids. Hand-authoring a partitioned {@code traces} in the test
 * instead would duplicate migration 000114 and reintroduce exactly the drift this suite exists to detect.
 *
 * <p><b>Topology covered, and the one cell that is not.</b> This suite runs the <b>post-EXCHANGE, pre-wrap</b> state on
 * purpose: {@code traces} is the partitioned successor and still a {@code MergeTree}, which is the state the pruning
 * flag has to hold in on its own — the wrap is a separate, deferrable cutover step ({@code --skip-wrap} now,
 * {@code --wrap-only} later), and prod-test sat in exactly this window. Applying the wrap here would remove that
 * coverage rather than add to it, since {@code partition_key} is meaningless once {@code traces} is {@code Distributed}.
 * The {@code traces_local} branch of this same template is executed by {@code TracesDistributedWrapMutationTest}, with
 * pruning off. So the untested cell is <b>both flags on at once</b>, and it stays untested here: the two are independent
 * StringTemplate attributes with no shared state, and the wrap is a {@code RENAME} — {@code traces_local} is the very
 * table this suite partitions and asserts against, so {@code id_at} and the partition key belong to the data, not to
 * the name it is reached by. Covering it needs a third topology (EXCHANGE + wrap + both flags) and therefore its own
 * suite; it cannot live in the wrap suite, which wraps the <em>legacy</em> table where this flag must be false.
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
     * The partition-key fragment the DAO's template emits. Only ever <b>compared against</b> the statement read back
     * from {@code system.query_log} — never spliced into a query this suite runs. Verbatim comparison is safe because
     * {@code query_log} stores the query as submitted, so both sides are the DAO's own template text.
     */
    private static final String PARTITION_PREDICATE = "toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))";

    // The suite's whole SQL surface, per .agents/skills/opik-backend/SKILL.md "SQL Query Construction": one text block
    // per query, every varying value a :placeholder. There are no StringTemplate fragments and no interpolation at all,
    // because nothing here re-implements the DAO's predicate - PARTITION_PREDICATE is only ever compared against the
    // statement the DAO emitted, never spliced into a query of ours.
    //
    // The EXCHANGE/RENAME pair in installPartitionedSuccessorUnderTraces() stays as inline literals on purpose: they
    // are single literals built by no Java string operation, and they are kept byte-identical to
    // 000003_exchange_and_wrap.sql by eye, so they belong at the call site next to the javadoc that says so - as
    // TracesDistributedWrapMutationTest does.
    private static final String TRACES_PARTITION_KEY = """
            SELECT partition_key
            FROM system.tables
            WHERE database = currentDatabase()
            AND name = 'traces'
            """;

    private static final String SUCCESSOR_TABLE_COUNT = """
            SELECT toString(count())
            FROM system.tables
            WHERE database = currentDatabase()
            AND name = 'traces_local_v2'
            """;

    private static final String INSERT_RAW_TRACE = """
            INSERT INTO traces (workspace_id, project_id, id)
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

    private static final String LIVE_ROW_COUNT = """
            SELECT toString(uniqExact(id))
            FROM traces
            WHERE workspace_id = :workspace_id
            AND id = :id
            """;

    /**
     * The {@code IN} clause the DAO emitted, captured to end of line: the predicate sits on its own line in the
     * template with {@code SETTINGS log_comment} on the next, so the line boundary delimits it exactly. Read this way
     * rather than by matching a bracket style, because the driver's rendering of a {@code Long[]} is its own choice —
     * what matters is which partition values are in the clause, not how it punctuates them.
     */
    private static final Pattern EMITTED_IN_CLAUSE = Pattern.compile(
            Pattern.quote(PARTITION_PREDICATE) + "\\s+IN\\s+([^\\n]*)");

    /**
     * The emitted statement's shape, so its {@code WHERE} clause can be lifted verbatim and re-asked as a
     * {@code SELECT}: {@code EXPLAIN} does not accept a mutation. Captures the target table too, since the DAO picks
     * {@code traces} or {@code traces_local} depending on the wrap flag.
     */
    private static final Pattern DELETE_SHAPE = Pattern.compile(
            "DELETE\\s+FROM\\s+(\\S+)\\s+(WHERE\\b.*?)\\s+SETTINGS\\b", Pattern.DOTALL);

    /** {@code EXPLAIN} index entries that reflect <b>partition</b>-level part selection. */
    private static final Set<String> PARTITION_INDEX_TYPES = Set.of("MinMax", "Partition");

    /** A weekly partition value as it appears in SQL — {@code yyyyMMdd}, so always eight digits. */
    private static final Pattern PARTITION_VALUE = Pattern.compile("\\d{8}");

    /**
     * One id per era the derivation has to get right, with the partition each resolves to. Not interchangeable samples:
     * {@code toMonday} agrees with the {@code Date32} expression across the ordinary calendar and diverges only
     * far-future or at the epoch, so a recent-only batch would accept the very expression migration 000114 was written
     * to escape. The 2200 id is the litellm shape and the one that makes the assertion bite.
     */
    private static final List<UUID> OUT_OF_WINDOW_ERA_IDS = List.of(
            UUID.fromString("00bfd451-fa93-7c10-9923-88a219a974c8"), // id_at 1996-02-09  -> 19960205
            UUID.fromString("0699eb8a-59dd-7215-8000-03b8d2a8d5e2")); // id_at 2200-01-01 -> 21991230

    private static final Set<Long> OUT_OF_WINDOW_ERA_PARTITIONS = Set.of(19960205L, 21991230L);

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
        ensurePartitionedSuccessorUnderTraces();
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
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
        assertThat(sql).as("the mutation carries the partition predicate").contains(PARTITION_PREDICATE);
        assertThat(boundPartitionsOf(sql))
                .as("bounded to exactly the target's own partition, nothing wider")
                .containsExactly(partitionOf(target.id()));
    }

    @Test
    @DisplayName("the DAO's own delete clears every era and binds exactly those partitions")
    void deleteClearsEveryEraAndBindsExactlyThosePartitions() {
        // The three-way agreement - the migration's PARTITION BY as installed, the DAO's predicate, and
        // WeeklyPartitions.of - asserted through the DAO's own delete instead of by re-evaluating the expression in
        // test SQL. If the predicate resolved to any partition other than the one ClickHouse filed a row under, the
        // mutation would select the wrong parts and that row would SURVIVE. So "every row is gone" IS the agreement,
        // and it is established by the statement production actually runs rather than by a query written here.
        //
        // This also covers the DateTime64 half of what the flag asserts, without asking system.columns: against a
        // 32-bit id_at the 2200 row would be stored under a wrapped recent timestamp, the derived partition would not
        // match it, and the row would survive.
        //
        // Three eras in one batch is also the multi-value Long[] bind, which a single-id delete never reaches.
        var recent = newTrace().build();
        traceResourceClient.createTrace(recent, API_KEY, WORKSPACE_NAME);
        var projectId = projectIdOf(recent);
        assertThat(projectId.version()).as("the project id is a real UUIDv7, as the backend mints it").isEqualTo(7);
        // Only the out-of-window eras are seeded raw: ingestion rejects a 1996 or 2200 id by design (24h window), so
        // there is no endpoint that can create them. The recent row above went through the real ingestion path, and
        // they all share its project.
        OUT_OF_WINDOW_ERA_IDS.forEach(id -> insertRawTrace(projectId, id));
        var batch = Stream.concat(Stream.of(recent.id()), OUT_OF_WINDOW_ERA_IDS.stream()).toList();
        assertThat(batch.stream().map(this::liveRowCount)).as("every era is present").containsOnly("1");

        delete(batch.stream().map(id -> Pair.of(projectId, id)).collect(Collectors.toUnmodifiableSet()));

        assertThat(batch.stream().map(this::liveRowCount))
                .as("every era's row is gone, so the predicate named the partition each was actually filed under")
                .containsOnly("0");
        var sql = lastTraceDeleteSql(recent.id());
        assertThat(sql).as("the mutation carries the partition predicate").contains(PARTITION_PREDICATE);
        assertThat(boundPartitionsOf(sql))
                .as("exactly the partitions the batch resolves to, not a range across three centuries")
                .containsExactlyInAnyOrderElementsOf(
                        Stream.concat(Stream.of(partitionOf(recent.id())), OUT_OF_WINDOW_ERA_PARTITIONS.stream())
                                .collect(Collectors.toUnmodifiableSet()));
    }

    @Test
    @DisplayName("the planner actually prunes, and the fallback provably does not")
    void pruningReachesThePlannerAndTheFallbackDoesNot() {
        // Correctness and pruning are different claims, and this is the only test that makes the second one. Deletes
        // were already correct before OPIK-6901 - what the change buys is parts touched (3,928/3,928 -> 5/3,928 on
        // prod-test), so a suite that cannot see pruning stop does not test what this change exists to do.
        //
        // The regression it guards is specific: a migration rewrites the partition expression to something semantically
        // identical but textually different, the planner stops recognising the DAO's predicate as the partition key,
        // pruning silently stops - and values still agree, so every row is still deleted and every other assertion in
        // this suite stays green. That is the property the removed AST pin covered; this asks the planner directly
        // instead of inferring it from text.
        //
        // EXPLAIN does not accept a mutation, so the WHERE clause is lifted verbatim out of the DAO's own emitted
        // DELETE and put behind a SELECT - predicate and bound partition values included. Only the verb changes; the
        // statement being explained is still the DAO's. Same instrument and record shape as
        // TracesLocalV2PartitioningTest.
        // Real project from the ingestion path, and a recent row created through the endpoint; only the out-of-window
        // eras are seeded raw, so the table holds several partitions to prune between.
        var recent = newTrace().build();
        traceResourceClient.createTrace(recent, API_KEY, WORKSPACE_NAME);
        var projectId = projectIdOf(recent);
        OUT_OF_WINDOW_ERA_IDS.forEach(id -> insertRawTrace(projectId, id));

        // Bounded: one derivable id, so the predicate names one of the partitions just populated.
        delete(Set.of(Pair.of(projectId, recent.id())));
        var bounded = partsSelectedBy(lastTraceDeleteSql(recent.id()))
                .orElseThrow(() -> new AssertionError("EXPLAIN reported no partition index for the bounded delete"));

        // Unbounded: a non-v7 id in the batch, so no predicate at all. Its partner is a different era, so the query_log
        // lookup finds this statement rather than the one above.
        var partner = OUT_OF_WINDOW_ERA_IDS.getFirst();
        delete(Set.of(Pair.of(projectId, partner), Pair.of(projectId, NON_V7_ID)));
        var unbounded = partsSelectedBy(lastTraceDeleteSql(partner));

        assertThat(bounded.selected())
                .as("the bounded delete selects fewer parts than the table holds: %s", bounded)
                .isLessThan(bounded.total());
        // Shown to discriminate, or it proves nothing - the same trap as `.contains(partition)` and
        // `doesNotContain("toDayOfWeek")`. The fallback must not prune: either the planner reports no partition index at
        // all, because nothing filters on the key, or it reports every part still selected.
        assertThat(unbounded.map(parts -> parts.selected() == parts.total()).orElse(true))
                .as("the fallback prunes nothing: %s", unbounded)
                .isTrue();
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("an id with no derivable partition disables pruning for the batch, and the delete still lands")
    void underivableIdDisablesPruning(String cause, UUID underivableId) {
        // The fallback that preserves the pre-OPIK-6901 guarantee, as the original javadoc stated it: a row whose id_at
        // cannot be trusted is STILL DELETED. That is a claim about the underivable row ITSELF, so it gets a real row
        // here - seeded raw, since ingestion rejects both id shapes by design. Passing it as an id matching nothing
        // would let an implementation that quietly drops underivable ids from the batch pass, which is the very bug the
        // all-or-nothing rule exists to prevent.
        var target = newTrace().build();
        traceResourceClient.createTrace(target, API_KEY, WORKSPACE_NAME);
        var projectId = projectIdOf(target);
        insertRawTrace(projectId, underivableId);
        assertThat(liveRowCount(underivableId)).as("the %s row is seeded before the delete", cause).isEqualTo("1");

        delete(Set.of(Pair.of(projectId, target.id()), Pair.of(projectId, underivableId)));

        assertThat(liveRowCount(underivableId))
                .as("the %s row is itself deleted, not skipped", cause)
                .isEqualTo("0");
        assertThat(traceIdsOf(target.projectName()))
                .as("and the derivable row batched alongside the %s id goes too", cause)
                .doesNotContain(target.id());

        // Asserted as the absence of ANY id_at predicate, not just of this PR's expression. A regression that narrowed
        // the mutation with toMonday(id_at), an id_at range, or any other partition predicate would skip exactly the
        // rows this fallback exists to reach, and rejecting one function name would not see it. The unbounded template
        // mentions id_at nowhere at all, so that is the whole check.
        var sql = lastTraceDeleteSql(target.id());
        assertThat(sql)
                .as("the unbounded form for a %s batch carries no id_at predicate of any kind", cause)
                .doesNotContain("id_at");
        assertThat(EMITTED_IN_CLAUSE.matcher(sql).find())
                .as("and no partition IN clause: %s", sql)
                .isFalse();
    }

    private static Stream<Arguments> underivableIdDisablesPruning() {
        return Stream.of(
                arguments("non-v7", NON_V7_ID),
                arguments("beyond-2299", OUT_OF_RANGE_ID));
    }

    /**
     * The partition a single id resolves to. Derived through {@code WeeklyPartitions} on purpose: this suite is about
     * the predicate reaching ClickHouse, and the derivation's own expected values are pinned against real ClickHouse
     * output in {@code WeeklyPartitionsTest}, so restating them here would only duplicate that. Where the expectation
     * needs to be readable on its own — the era batch — the partitions are stated as literals instead.
     */
    private static long partitionOf(UUID id) {
        return WeeklyPartitions.of(List.of(id)).orElseThrow().iterator().next();
    }

    /**
     * The partition values actually bound into the emitted {@code IN} clause, so a test can assert the set is exact
     * rather than merely inclusive. The driver substitutes bound values into the query text client-side, which is why
     * they are readable here at all; the two assertions below are what make a change in that behaviour say so plainly
     * instead of quietly turning every set assertion into a tautology on an empty set.
     */
    private static Set<Long> boundPartitionsOf(String sql) {
        var clause = EMITTED_IN_CLAUSE.matcher(sql);
        assertThat(clause.find())
                .as("the delete SQL carries the partition predicate followed by an IN clause:%n%s", sql)
                .isTrue();
        var bound = PARTITION_VALUE.matcher(clause.group(1)).results()
                .map(match -> Long.parseLong(match.group()))
                .collect(Collectors.toUnmodifiableSet());
        assertThat(bound)
                .as("the IN clause carries inlined partition values — got '%s'", clause.group(1))
                .isNotEmpty();
        return bound;
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
        execute("SYSTEM FLUSH LOGS", _ -> {
        });
        var sql = queryOneString(LAST_TRACE_DELETE, statement -> statement.bind("trace_id", traceId.toString()));
        assertThat(sql)
                .as("query_log holds a delete_traces statement mentioning id '%s'", traceId)
                .isNotNull();
        return sql;
    }

    /** {@code "1"} while a live (non-lightweight-deleted) row exists for the id, {@code "0"} once it is gone. */
    private String liveRowCount(UUID id) {
        return queryOneString(LIVE_ROW_COUNT, statement -> statement
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
     * deliberately not applied — it is a separate, deferrable step, and the flag under test must hold on its own between
     * the two (which is why it is not the wrap flag). Kept identical to the cutover SQL by eye, as
     * {@code TracesLocalV2CutoverTest.exchangeTables} and the wrap suite do.
     */
    private void ensurePartitionedSuccessorUnderTraces() {
        if (queryOneString(TRACES_PARTITION_KEY, _ -> {
        }).contains("id_at")) {
            return; // The cutover migration has landed: `traces` already is the partitioned successor.
        }
        assertThat(queryOneString(SUCCESSOR_TABLE_COUNT, _ -> {
        }))
                .as("`traces` is not partitioned and `traces_local_v2` does not exist, so there is no successor to"
                        + " install - this suite needs one of those two states")
                .isEqualTo("1");
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
     * Parts the planner selects for the DAO's own statement, or empty when it reports no partition index at all.
     * <p>
     * {@code SELECT id} rather than {@code count()}, so no trivial-count optimisation can answer from metadata without
     * selecting parts at all.
     * <p>
     * Only {@code MinMax} and {@code Partition} entries are considered, and {@code PrimaryKey} is deliberately
     * excluded: the DAO's {@code WHERE} also filters {@code workspace_id} and {@code (project_id, id)}, which are the
     * sort key, so {@code PrimaryKey} prunes parts for the <b>unbounded</b> statement too — counting it would make the
     * fallback look pruned and destroy the discrimination this test rests on. Across the entries that do qualify it
     * takes the smallest selected count and the largest initial count, so it does not depend on which of the two
     * reports the pruning.
     * <p>
     * Empty is a meaningful answer rather than a failure: the {@code Indexes} block carries a partition entry only when
     * the query filters on the partition key, so its absence is exactly what the fallback should produce.
     */
    private Optional<SelectedParts> partsSelectedBy(String daoDeleteSql) {
        var shape = DELETE_SHAPE.matcher(daoDeleteSql);
        assertThat(shape.find())
                .as("the emitted statement has the expected DELETE shape:%n%s", daoDeleteSql)
                .isTrue();
        var selectSql = "SELECT id FROM %s %s".formatted(shape.group(1), shape.group(2));

        var explainRows = template.stream(connection -> Flux
                .from(connection.createStatement("EXPLAIN indexes = 1, json = 1 %s".formatted(selectSql)).execute())
                .flatMap(result -> result.map((row, _) -> row.get("explain", String.class))))
                .collectList()
                .block();
        var explain = String.join("\n", explainRows);

        var indexes = JsonUtils.getJsonNodeFromString(explain).findValue("Indexes");
        if (indexes == null) {
            return Optional.empty();
        }
        SelectedParts partition = null;
        for (JsonNode index : indexes) {
            if (!PARTITION_INDEX_TYPES.contains(index.path("Type").asText()) || !index.has("Selected Parts")) {
                continue;
            }
            var entry = JsonUtils.treeToValue(index, SelectedParts.class);
            partition = partition == null
                    ? entry
                    : new SelectedParts(Math.min(partition.selected(), entry.selected()),
                            Math.max(partition.total(), entry.total()));
        }
        return Optional.ofNullable(partition);
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

    /**
     * The part counts {@code EXPLAIN indexes = 1, json = 1} reports for one index entry: how many parts the query
     * started from, and how many survived pruning.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SelectedParts(
            @JsonProperty("Selected Parts") int selected,
            @JsonProperty("Initial Parts") int total) {
    }
}
