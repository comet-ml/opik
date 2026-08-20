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
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.WeeklyPartitions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Statement;
import lombok.Builder;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * <p><b>Why the topology setup is here at all — it is setup, never the subject.</b> Nothing asserts anything about the
 * EXCHANGE or the wrap themselves; {@code TracesLocalV2CutoverTest} owns that. They are required because the DAO names
 * its target table: after the Liquibase migrations the live {@code traces} is still the <em>legacy</em> table — no
 * {@code PARTITION BY} at all and a 32-bit {@code DateTime} {@code id_at} — while the partitioned successor exists only
 * as the empty {@code traces_local_v2}, which no DAO query can reach. Without installing it these tests would run
 * against the one table where this predicate must never be emitted, and would pass while proving nothing: the predicate
 * is harmless against an unpartitioned table for recent ids. Hand-authoring a partitioned {@code traces} in the test
 * instead would duplicate migration 000114 and reintroduce exactly the drift this suite exists to detect. Both steps are
 * idempotent, so the suite keeps working once the cutover migration lands and they become no-ops.
 *
 * <p><b>Topology: the post-cutover end state, with both schema flags on.</b> The wrap is applied and
 * {@code tracesDistributedWrapEnabled} set, so the DAO's mutations reach the data the way production routes them —
 * {@code DELETE FROM traces_local}, chosen by the configuration switch that governs it, not by a table this suite
 * renamed under the DAO. {@code traces} is the {@code Distributed} wrapper that reads and inserts flow through, which
 * is why the endpoint-created row and the raw-seeded ones land in the same place.
 * {@link #distributedTracesRejectsDirectMutation} keeps that claim honest: had the wrap not taken effect,
 * {@code traces} would still be a {@code MergeTree} and every pruned delete here would have run against it.
 *
 * <p>This supersedes an earlier note in this file that both flags on at once was untested and would need its own suite.
 * It does not: the wrap flag is how the DAO is pointed at the data, so enabling it costs two setup statements and
 * covers the combination the fleet actually ends up in. What is no longer covered here is the transient
 * post-EXCHANGE/pre-wrap window — the pruning predicate is identical in both, since {@code traces_local} is the same
 * physical table under a different name, and the flag's own javadoc records that it must hold in that window.
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

    private static final String TABLE_ENGINE = """
            SELECT engine
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

    private static final String LIVE_ROW_COUNT = """
            SELECT toString(uniqExact(id))
            FROM traces_local
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
     * that makes it bite; it also covers the {@code DateTime64} half of what the flag asserts, since a 32-bit
     * {@code id_at} would store it under a wrapped recent timestamp and the derived partition would miss it.
     */
    private static final List<LocalDate> ERA_MONDAYS = List.of(
            LocalDate.of(1996, 2, 5),
            LocalDate.of(2025, 3, 3),
            LocalDate.of(2199, 12, 30));

    /** {@link UUID#randomUUID()} is a v4 by definition: no embedded timestamp to derive a partition from. */
    private static final UUID NON_V7_ID = UUID.randomUUID();

    /**
     * A UUIDv7 minted one second past the first instant {@code DateTime64} cannot represent, so its {@code id_at}
     * saturates to the ceiling and the honest week is not the partition the row lands in. Same rejection as
     * {@link #NON_V7_ID}, different cause — see {@code WeeklyPartitions}. Minted rather than written out, so the
     * boundary it sits past is visible.
     */
    private static final UUID OUT_OF_RANGE_ID = ID_GENERATOR
            .getTimeOrderedEpoch(LocalDate.of(2300, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli());

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
                                        "true"),
                                new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true")))
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
        ensureDistributedWrap();
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    @Test
    @DisplayName("traces really is a mutation-rejecting Distributed wrapper, so the deletes here ran on traces_local")
    void distributedTracesRejectsDirectMutation() {
        // Keeps the both-flags claim from being vacuous. If the wrap had not taken effect, `traces` would still be a
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
        assertThat(sql).as("the mutation carries the partition predicate").contains(PARTITION_PREDICATE);
        assertThat(boundPartitionsOf(sql))
                .as("bounded to exactly the target's own partition, nothing wider")
                .containsExactly(partitionOf(target.id()));
    }

    @Test
    @DisplayName("the DAO's own delete clears every era and binds exactly those partitions")
    void deleteClearsEveryEraAndBindsExactlyThosePartitions() {
        // The three-way agreement - the migration's PARTITION BY as installed, the DAO's predicate, and
        // WeeklyPartitions.of - asserted through the DAO's own delete rather than by re-evaluating the expression in
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
        assertThat(ids.stream().map(this::liveRowCount)).as("every era is seeded").containsOnly("1");

        delete(ids.stream().map(id -> Pair.of(projectId, id)).collect(Collectors.toUnmodifiableSet()));

        assertThat(ids.stream().map(this::liveRowCount))
                .as("every era's row is gone, so the predicate named the partition each was actually filed under")
                .containsOnly("0");
        var sql = lastTraceDeleteSql(ids.getFirst());
        assertThat(sql).as("the mutation carries the partition predicate").contains(PARTITION_PREDICATE);
        assertThat(boundPartitionsOf(sql))
                .as("exactly the partitions the batch resolves to, not a range across two centuries")
                .containsExactlyInAnyOrderElementsOf(ERA_MONDAYS.stream()
                        .map(TracesPartitionPruningMutationTest::partitionNameOf)
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
        // One row per era, so the table holds several partitions for the planner to prune between.
        var projectId = ID_GENERATOR.generateId();
        var ids = ERA_MONDAYS.stream().map(TracesPartitionPruningMutationTest::idInWeekOf).toList();
        ids.forEach(id -> insertRawTrace(projectId, id));

        // Bounded: one derivable id, so the predicate names a single one of those partitions.
        delete(Set.of(Pair.of(projectId, ids.getFirst())));
        var bounded = partsSelectedBy(lastTraceDeleteSql(ids.getFirst()))
                .orElseThrow(() -> new AssertionError("EXPLAIN reported no partition index for the bounded delete"));

        // Unbounded: a non-v7 id in the batch, so no predicate at all. Its partner is a different era, so the query_log
        // lookup finds this statement rather than the one above.
        var partner = ids.get(1);
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
        if ("Distributed".equals(queryOneString(TABLE_ENGINE, _ -> {
        }))) {
            return;
        }
        // Clear a wrapper left behind by a run that died between the CREATE and the RENAME. It holds no data - a
        // Distributed table is a routing definition - so dropping it is safe, and without this the CREATE below fails
        // on a duplicate name and buries the real state. Same reset TracesLocalV2CutoverTest performs.
        execute("DROP TABLE IF EXISTS traces_dist ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("""
                CREATE TABLE traces_dist ON CLUSTER '{cluster}' AS traces
                ENGINE = Distributed('{cluster}', '%s', 'traces_local', sipHash64(project_id))
                """.formatted(ClickHouseContainerUtils.DATABASE_NAME), _ -> {
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
                    : partition.toBuilder()
                            .selected(Math.min(partition.selected(), entry.selected()))
                            .total(Math.max(partition.total(), entry.total()))
                            .build();
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
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SelectedParts(
            @JsonProperty("Selected Parts") int selected,
            @JsonProperty("Initial Parts") int total) {
    }
}
