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
import com.comet.opik.utils.template.TemplateUtils;
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
 * <p>Each test asserts <b>both</b> halves, because either alone passes for the wrong reason: the rows are read back
 * through the public API (a delete that pruned to a partition the row is not in would leave it behind), and the SQL
 * ClickHouse actually received is read back from {@code system.query_log} (a delete that silently stopped pruning would
 * still remove the row, just slowly — the regression the flag and the derivation exist to prevent, and one no
 * behavioural assertion can see).
 *
 * <p>{@link #predicateMatchesLivePartitioningAndJavaDerivation} and {@link #idAtIsTheSixtyFourBitColumn} are the guards
 * that keep the rest honest, together pinning both facts
 * {@code databaseAnalyticsDataModel.tracesWeeklyPartitionPruningEnabled} asserts. They are load-bearing twice over. The
 * predicate is harmless against an unpartitioned table for recent ids, so had the EXCHANGE below not taken effect every
 * other test here would still pass while proving nothing. And the rule itself is expressed three times over — the
 * migration's {@code PARTITION BY}, the DAO's predicate, and {@link WeeklyPartitions#of} — so the first guard makes all
 * three compute the same value for the same row, across the eras where a plausible wrong expression
 * ({@code toMonday}) would diverge.
 *
 * <p>Two internal touches, on the pattern of {@code TracesDistributedWrapMutationTest}: the EXCHANGE has no public API,
 * so {@link #beforeAll} runs it in raw SQL identical to the swap block of {@code 000003_exchange_and_wrap.sql}; and the
 * ingestion path rejects a non-v7 or far-future {@code id} by design ({@code IdGenerator.validateId}), so the batches
 * that must <b>not</b> prune are handed to {@link TraceDAO#delete} directly — the only way to reach that arm.
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
     * The partition-key fragment the template emits. Compared verbatim against the SQL read back from
     * {@code system.query_log}, which is safe because that is the query text as submitted — the DAO's own template
     * string, not a re-print.
     */
    private static final String PARTITION_PREDICATE = "toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))";

    /**
     * Throwaway table used only to have ClickHouse re-print the DAO predicate as a partition key.
     * Created and dropped in-test.
     */
    private static final String PARTITION_KEY_PROBE = "traces_partition_key_probe";

    /** Project for the raw-SQL seeded rows, kept off the API-created projects so neither test's reads see the other's. */
    private static final UUID RAW_PROJECT_ID = UUID.randomUUID();

    // The suite's SQL, per .agents/skills/opik-backend/SKILL.md "SQL Query Construction": each query declared once as a
    // text block, values as :placeholders, and the two things that cannot be bound - a partition-key EXPRESSION and a
    // table IDENTIFIER - as StringTemplate fragments rendered through TemplateUtils.newST, following
    // ClickHousePartitionMetricsDAO. Both fragments are compile-time constants of this class, never test input, so
    // neither needs the allow-list guard that DAO's isValidTable applies to its configured table list.
    //
    // The EXCHANGE/RENAME pair in exchangeTables() stays as inline literals on purpose: they are single literals built
    // by no Java string operation, and they are kept byte-identical to 000003_exchange_and_wrap.sql by eye, so they
    // belong at the call site next to the javadoc that says so - as TracesDistributedWrapMutationTest does.
    private static final String SELECT_PARTITION_ID = """
            SELECT DISTINCT _partition_id
            FROM traces
            WHERE workspace_id = :workspace_id
            AND id = :id
            """;

    private static final String SELECT_PARTITION_EXPRESSION_VALUE = """
            SELECT DISTINCT toString(<partition_expression>)
            FROM traces
            WHERE workspace_id = :workspace_id
            AND id = :id
            """;

    private static final String CREATE_PARTITION_KEY_PROBE = """
            CREATE TABLE <probe_table>
            (
                id_at DateTime64(0, 'UTC')
            )
            ENGINE = MergeTree
            PARTITION BY <partition_expression>
            ORDER BY tuple()
            """;

    private static final String DROP_PARTITION_KEY_PROBE = """
            DROP TABLE IF EXISTS <probe_table> SYNC
            """;

    private static final String SELECT_ID_AT_TYPE = """
            SELECT type
            FROM system.columns
            WHERE database = currentDatabase()
            AND table = 'traces'
            AND name = 'id_at'
            """;

    private static final String SELECT_PARTITION_KEY = """
            SELECT partition_key
            FROM system.tables
            WHERE database = currentDatabase()
            AND name = :table
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

    /** A weekly partition value as it appears in SQL — {@code yyyyMMdd}, so always eight digits. */
    private static final Pattern PARTITION_VALUE = Pattern.compile("\\d{8}");

    /** An id in a different week from anything the API mints, for the two-partition batch. id_at 2023-11-29 (a Wed). */
    private static final UUID OTHER_WEEK_ID = UUID.fromString("018c1860-1800-7abc-8000-000000000001");

    /** The Monday of {@link #OTHER_WEEK_ID}'s week — stated as a literal so the expectation is readable on its own. */
    private static final long OTHER_WEEK_PARTITION = 20231127L;

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

    @ParameterizedTest
    @MethodSource
    @DisplayName("the DAO predicate, the live partitioning and the Java derivation agree exactly")
    void predicateMatchesLivePartitioningAndJavaDerivation(String era, UUID id, long expectedPartition) {
        // The guard the rest of the suite rests on, and the one that catches drift. Three independently-maintained
        // expressions of the same rule have to agree, or a delete prunes to a partition its rows are not in:
        //
        //   1. the migration's PARTITION BY, as ClickHouse actually installed it   -> _partition_id, where it filed the row
        //   2. the DAO's predicate                                                 -> PARTITION_PREDICATE, evaluated here
        //   3. WeeklyPartitions.of                                                 -> what gets bound to :partitions
        //
        // Compared as VALUES, not as normalized expression text. A text comparison would pin (1) against (2) and say
        // nothing about (3), and it would pass for a rewrite that is textually equal after normalization yet computes a
        // different week — which is precisely the toMonday trap migration 000114 was written to escape. Values also make
        // the check immune to ClickHouse's re-printing of the AST, which is what made the previous substring form loose.
        //
        // The era matters: toMonday agrees with the Date32 expression across the ordinary calendar and diverges only for
        // a far-future or epoch id_at, so a sample set that stopped at "recent" would accept the wrong expression. The
        // rows are inserted in raw SQL because ingestion rejects a backdated or far-future id by design; only
        // (workspace_id, project_id, id) are supplied, since id_at is MATERIALIZED and every other column has a DEFAULT
        // — so this seeds through the real column definition rather than restating it.
        insertRawTrace(id);

        var filedUnder = queryOneString(SELECT_PARTITION_ID, bindRawTrace(id));
        // The DAO predicate goes in as a StringTemplate fragment, not a bind: it is an expression to be evaluated, and
        // evaluating the DAO's own text is the entire point. The workspace and id are values, so they bind.
        var daoPredicateValue = queryOneString(withPartitionExpression(SELECT_PARTITION_EXPRESSION_VALUE),
                bindRawTrace(id));

        assertThat(daoPredicateValue)
                .as("the DAO predicate resolves to the partition ClickHouse filed the %s row under", era)
                .isEqualTo(filedUnder);
        assertThat(WeeklyPartitions.of(List.of(id)))
                .as("the Java derivation agrees with both for the %s row", era)
                .contains(Set.of(expectedPartition));
        assertThat(filedUnder)
                .as("and all three are the expected partition for the %s row", era)
                .isEqualTo(String.valueOf(expectedPartition));
    }

    private static Stream<Arguments> predicateMatchesLivePartitioningAndJavaDerivation() {
        return Stream.of(
                // id_at 2026-08-19 (Wed) -> Monday 2026-08-17. The ordinary case; toMonday would also pass this one.
                arguments("recent", UUID.fromString("01a01a75-76de-785e-ae84-8870ed5e6db3"), 20260817L),
                // id_at 1996-02-09 -> Monday 1996-02-05. Far enough back to catch a key that keyed off wall-clock.
                arguments("backdated", UUID.fromString("00bfd451-fa93-7c10-9923-88a219a974c8"), 19960205L),
                // id_at 2200-01-01 -> Monday 2199-12-30. The litellm shape, and the sample a 16-bit toMonday key wraps
                // into a plausible recent week (000114) — so this row is what makes the guard bite.
                arguments("far-future", UUID.fromString("0699eb8a-59dd-7215-8000-03b8d2a8d5e2"), 21991230L));
    }

    @Test
    @DisplayName("the DAO predicate is the same expression as the live partition key, not merely an equal-valued one")
    void daoPredicateIsTheSameExpressionAsTheLivePartitionKey() {
        // Value agreement (above) proves the predicate names the right partition; it does NOT prove ClickHouse will
        // PRUNE on it. Pruning needs the planner to recognise the predicate as being on the partition key expression,
        // so an equal-valued but differently-written expression would keep every delete correct and quietly rewrite
        // every part again - the exact regression this PR exists to prevent, invisible to every other assertion here.
        //
        // Compared by round-tripping the DAO's text through ClickHouse as a partition key of its own and diffing the
        // two re-prints. That makes the comparison AST-level and formatter-independent by construction: both strings
        // come out of the same printer, so they are equal iff the parsed expressions are. Diffing the DAO text against
        // system.tables directly would instead pin ClickHouse's whitespace choices, which is what it must not do.
        execute(createProbeTableSql(), _ -> {
        });
        try {
            assertThat(partitionKeyOf(PARTITION_KEY_PROBE))
                    .as("the DAO predicate parses to the same expression traces is partitioned by")
                    .isEqualTo(partitionKeyOf("traces"));
        } finally {
            execute(withProbeTable(DROP_PARTITION_KEY_PROBE), _ -> {
            });
        }
    }

    @Test
    @DisplayName("id_at is the 64-bit column, so a far-future timestamp is honest rather than wrapped")
    void idAtIsTheSixtyFourBitColumn() {
        // The second half of what the flag asserts, and not implied by the partition agreement above: a 32-bit
        // DateTime id_at would agree with itself while silently wrapping every id past 2106.
        assertThat(queryOneString(SELECT_ID_AT_TYPE)).isEqualTo("DateTime64(0, 'UTC')");
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
    @DisplayName("a batch spanning two weeks binds both partitions, not a range")
    void batchSpanningTwoWeeksBindsBothPartitions() {
        // The multi-value Long[] bind, which the single-id path never exercises. The second id is minted for a week
        // three years back and matches no row — the batch's partition SET is what is under test, and a delete does not
        // need its ids to exist.
        //
        // Asserted as an EXACT set, because "mentions both partitions" is satisfied by a binding that also names every
        // week in between, and that is the failure worth catching: an over-broad set keeps every delete correct while
        // giving back the entire benefit. On prod-test a range over this span selected 2,644 of 3,928 parts where the
        // exact set selected 4 — so a delete that is right and slow is the regression, and no behavioural assertion
        // can see it.
        var target = newTrace().build();
        traceResourceClient.createTrace(target, API_KEY, WORKSPACE_NAME);
        var projectId = projectIdOf(target);

        delete(Set.of(Pair.of(projectId, target.id()), Pair.of(projectId, OTHER_WEEK_ID)));

        assertThat(traceIdsOf(target.projectName())).doesNotContain(target.id());
        var sql = lastTraceDeleteSql(target.id());
        assertThat(sql).as("the mutation carries the partition predicate").contains(PARTITION_PREDICATE);
        assertThat(boundPartitionsOf(sql))
                .as("exactly the batch's own two partitions, not a range across them")
                .containsExactlyInAnyOrder(partitionOf(target.id()), OTHER_WEEK_PARTITION);
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
     * output in {@code WeeklyPartitionsTest} and again in
     * {@link #predicateMatchesLivePartitioningAndJavaDerivation}, so restating them here would only duplicate that.
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

    /**
     * Seeds one row through the table's real column definitions. Only the three columns without a {@code DEFAULT} are
     * supplied; {@code id_at} is {@code MATERIALIZED}, so ClickHouse derives it from {@code id} exactly as it does for a
     * row the ingestion path wrote — which is the point, since restating the {@code id_at} expression here would create
     * the very drift surface this test exists to detect. Raw SQL rather than the API because ingestion rejects a
     * backdated or far-future {@code id} by design ({@code IdGenerator.validateId}).
     */
    private void insertRawTrace(UUID id) {
        insertRawTrace(RAW_PROJECT_ID, id);
    }

    /** As {@link #insertRawTrace(UUID)}, into a caller-chosen project, so a seeded row can share a test's project. */
    private void insertRawTrace(UUID projectId, UUID id) {
        execute(INSERT_RAW_TRACE,
                statement -> statement
                        .bind("workspace_id", WORKSPACE_ID)
                        .bind("project_id", projectId.toString())
                        .bind("id", id.toString()));
    }

    /**
     * Renders a template whose only fragment is the DAO's partition-key expression — an expression, not a value, so it
     * cannot be bound. Each renderer adds exactly the attributes its template declares, as the DAOs do.
     */
    private static String withPartitionExpression(String sql) {
        return TemplateUtils.newST(sql)
                .add("partition_expression", PARTITION_PREDICATE)
                .render();
    }

    /**
     * As {@link #withPartitionExpression}, for the probe-table statements: a table identifier cannot be bound either.
     */
    private static String withProbeTable(String sql) {
        return TemplateUtils.newST(sql)
                .add("probe_table", PARTITION_KEY_PROBE)
                .render();
    }

    /** The probe-table DDL carries both fragments. */
    private static String createProbeTableSql() {
        return TemplateUtils.newST(CREATE_PARTITION_KEY_PROBE)
                .add("probe_table", PARTITION_KEY_PROBE)
                .add("partition_expression", PARTITION_PREDICATE)
                .render();
    }

    /** ClickHouse's own re-print of a table's partition key expression. */
    private String partitionKeyOf(String table) {
        return queryOneString(SELECT_PARTITION_KEY, statement -> statement.bind("table", table));
    }

    private static Consumer<Statement> bindRawTrace(UUID id) {
        return statement -> statement
                .bind("workspace_id", WORKSPACE_ID)
                .bind("id", id.toString());
    }

    private String queryOneString(String sql) {
        return queryOneString(sql, _ -> {
        });
    }

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
}
