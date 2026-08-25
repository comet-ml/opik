package com.comet.opik.infrastructure;

import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
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
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
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

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The other side of {@code TracesPartitionPruningMutationTest}: the same trace delete, with the same rendered partition
 * predicate, against the <b>legacy</b> {@code traces} — no {@code PARTITION BY} at all and {@code id_at} declared as a
 * 32-bit {@code DateTime} (migrations 000001 and 000091). That is not an exotic topology; it is the state every
 * deployment is in today, and the state a stage B/C rollback returns to.
 *
 * <p><b>What it guards.</b> The predicate used to be gated on a configuration flag asserting the EXCHANGE had already
 * happened, because on this table a set derived from the {@code DateTime64} successor is a predicate that matches
 * nothing for a far-future id: the 32-bit column holds {@code epochSecond % 2^32}, so a litellm-shaped id
 * (<a href="https://github.com/BerriAI/litellm/issues/31294">BerriAI/litellm#31294</a> mints ~2201) is stored under a
 * wrapped recent timestamp and the delete would report success having matched <b>zero</b> rows.
 * {@link WeeklyPartitions} now names both weeks, which is what removed the flag — and this suite is what holds that
 * claim up. It is the only place the legacy representation is checked against a real ClickHouse rather than asserted
 * about; if that conversion ever stops being a modulo, {@link #farFutureRowOnLegacyTableIsDeletedByAPrunedStatement}
 * fails here instead of a delete silently skipping rows in a pre-cutover deployment.
 *
 * <p>Nothing else catches it. Every other trace-delete suite runs in exactly this state and asserts only that rows go
 * away — which they do for a <b>recent</b> id, because the legacy {@code id_at} is accurate for one. No existing test
 * has a far-future row, because ingestion rejects such ids by design ({@code IdGenerator.validateId}) — so this seeds
 * one in raw SQL and deletes it through {@link TraceDAO#delete}, which is also the only way to reach that arm.
 *
 * <p>Shared, reusable containers: unlike the post-EXCHANGE suite this changes no topology, so there is nothing here
 * that could corrupt another suite or a rerun.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesLegacyTablePruningMutationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    /** A UUIDv7 carrying id_at 2200-01-01 — the litellm shape, and the id the legacy 32-bit column wraps. */
    private static final UUID FAR_FUTURE_ID = UUID.fromString("0699eb8a-59dd-7215-8000-03b8d2a8d5e2");

    /**
     * The week that id partitions into under a {@code DateTime64} {@code id_at} — its honest one, and the only value the
     * flag-gated predicate used to carry. Stated as a literal because it is what ClickHouse returned for
     * {@code toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))} on that id, not something re-derived
     * here.
     */
    private static final long HONEST_WEEK = 21991230L;

    /**
     * The week the same id partitions into on <b>this</b> table: {@code CAST(UUIDv7ToDateTime(...) AS DateTime('UTC'))}
     * wraps 2200-01-01 to 2063-11-25, a Wednesday, whose Monday is 2063-11-19. This is the value that makes the delete
     * below land, and the reason the predicate needs no flag.
     */
    private static final long LEGACY_WEEK = 20631119L;

    /** {@link UUID#randomUUID()} is a v4 by definition: no embedded timestamp to derive a partition from. */
    private static final UUID NON_V7_ID = UUID.randomUUID();

    /**
     * The partition-key fragment the DAO's template emits. Only ever <b>compared against</b> the statement read back
     * from {@code system.query_log} — never spliced into a query this suite runs.
     */
    private static final String PARTITION_PREDICATE = "toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))";

    /**
     * The {@code IN} clause the DAO emitted, captured to end of line: the predicate sits on its own line in the
     * template with {@code SETTINGS log_comment} on the next, so the line boundary delimits it exactly.
     */
    private static final Pattern EMITTED_IN_CLAUSE = Pattern.compile(
            Pattern.quote(PARTITION_PREDICATE) + "\\s+IN\\s+([^\\n]*)");

    /** A weekly partition value as it appears in SQL — {@code yyyyMMdd}, so always eight digits. */
    private static final Pattern PARTITION_VALUE = Pattern.compile("\\d{8}");

    private static final String INSERT_RAW_TRACE = """
            INSERT INTO traces (workspace_id, project_id, id)
            VALUES (:workspace_id, :project_id, :id)
            """;

    /**
     * Scoped by the same key {@code TraceDAO.delete} matches on — {@code (workspace_id, project_id, id)}. An oracle
     * narrower than the delete answers a different question, and this suite runs on shared containers where other
     * suites' rows are present, so the scoping is what keeps the count about this test's row.
     */
    private static final String LIVE_ROW_COUNT = """
            SELECT toString(uniqExact(id))
            FROM traces
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND id = :id
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

    private static final String LEGACY_SCHEMA = """
            SELECT concat(
                (SELECT partition_key FROM system.tables
                    WHERE database = currentDatabase() AND name = 'traces'),
                '|',
                (SELECT type FROM system.columns
                    WHERE database = currentDatabase() AND table = 'traces' AND name = 'id_at'))
            """;

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);

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
        // No customConfigs on purpose: the defaults ARE the state under test.
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
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

        // This whole suite is about the pre-cutover / post-rollback state. Once the cutover migration lands there is no
        // legacy `traces` left to test - it becomes the partitioned successor with a DateTime64 id_at - and the
        // far-future wrapping hazard this guards stops existing. Skip with the reason stated rather than fail on a
        // premise that has legitimately gone away; JUnit reports the skip and its description in the test report.
        assumeThat(legacySchema())
                .as("pre-cutover estate: legacy `traces`, unpartitioned with a 32-bit DateTime id_at")
                .isEqualTo("|DateTime('UTC')");
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("a far-future row on the legacy table is deleted by the same pruned statement")
    void farFutureRowOnLegacyTableIsDeletedByAPrunedStatement() {
        // The one test that shows the union is load-bearing rather than decorative. With only the honest week in the
        // set, this delete matches nothing on this table and reports success - which is exactly the failure the
        // configuration flag used to exist to avoid, now avoided by the predicate itself.
        //
        // The project and its id come from the real ingestion path - create a trace through the endpoint, then read the
        // project id back off it - so the delete runs against a project that exists and a genuine UUIDv7 project id,
        // not a fabricated one.
        var projectId = projectIdOf(createTrace());

        // Only the far-future row is raw, because ingestion rejects it by design (24h window). A recent id would prove
        // nothing here: the legacy id_at is accurate for one, so even a honest-week-only predicate would match it.
        insertRawTrace(projectId, FAR_FUTURE_ID);
        assertThat(liveRowCount(projectId, FAR_FUTURE_ID)).as("the far-future row is seeded").isEqualTo("1");

        delete(Set.of(Pair.of(projectId, FAR_FUTURE_ID)));

        assertThat(liveRowCount(projectId, FAR_FUTURE_ID))
                .as("it is deleted - so the predicate named the week THIS table filed it under, not only the honest one")
                .isEqualTo("0");

        var sql = lastTraceDeleteSql(FAR_FUTURE_ID);
        assertThat(sql)
                .as("the predicate is emitted here too - there is no schema flag holding it back any more")
                .contains(PARTITION_PREDICATE);
        // Asserted as an exact set, both ways round. Only the legacy week can match a row on this table, so a set
        // missing it would fail the delete above; and a set missing the honest week would pass here while breaking the
        // post-EXCHANGE suite, which is the pair this has to stay consistent with.
        assertThat(boundPartitionsOf(sql))
                .as("both representations of the same id: the honest week and the one the 32-bit column wraps it to")
                .containsExactlyInAnyOrder(HONEST_WEEK, LEGACY_WEEK);
        assertThat(WeeklyPartitions.of(List.of(FAR_FUTURE_ID)))
                .as("and the derivation this suite pins is the one the DAO used")
                .contains(Set.of(HONEST_WEEK, LEGACY_WEEK));
    }

    @Test
    @DisplayName("an ordinary row is deleted by a single-week statement, since both id_at types agree below 2106")
    void ordinaryRowIsBoundedToOneWeek() {
        // The counterweight: the union widens only where the two representations differ, so real traffic binds exactly
        // what it bound before. A regression that added the wrapped week unconditionally would show up here.
        var target = createTrace();
        var projectId = projectIdOf(target);
        assertThat(liveRowCount(projectId, target.id())).as("the row is there").isEqualTo("1");

        traceResourceClient.deleteTrace(target.id(), WORKSPACE_NAME, API_KEY);

        assertThat(liveRowCount(projectId, target.id())).as("and it is deleted").isEqualTo("0");
        var sql = lastTraceDeleteSql(target.id());
        assertThat(sql).as("the mutation carries the partition predicate").contains(PARTITION_PREDICATE);
        assertThat(boundPartitionsOf(sql))
                .as("one week, not two: a recent id_at is inside the 32-bit range, so the two derivations coincide")
                .hasSize(1);
    }

    @Test
    @DisplayName("a non-v7 id still disables pruning here, and the delete still lands")
    void nonV7IdDisablesPruning() {
        // The fallback is unchanged by the flag removal and still has to hold on this table: a row whose id_at cannot
        // be trusted is STILL DELETED, by an unbounded mutation. Seeded raw, since ingestion rejects a non-v7 id.
        var target = createTrace();
        var projectId = projectIdOf(target);
        insertRawTrace(projectId, NON_V7_ID);
        assertThat(liveRowCount(projectId, NON_V7_ID)).as("the non-v7 row is seeded").isEqualTo("1");

        delete(Set.of(Pair.of(projectId, target.id()), Pair.of(projectId, NON_V7_ID)));

        assertThat(liveRowCount(projectId, NON_V7_ID))
                .as("the non-v7 row is itself deleted, not skipped")
                .isEqualTo("0");
        assertThat(liveRowCount(projectId, target.id()))
                .as("and so is the derivable row batched alongside it")
                .isEqualTo("0");

        // Asserted as the absence of ANY id_at predicate, not just of this expression: a regression that narrowed the
        // mutation with toMonday(id_at), an id_at range, or anything else would skip exactly the rows this reaches.
        var sql = lastTraceDeleteSql(NON_V7_ID);
        assertThat(sql)
                .as("the unbounded form carries no id_at predicate of any kind")
                .doesNotContain("id_at");
        assertThat(EMITTED_IN_CLAUSE.matcher(sql).find())
                .as("and no partition IN clause: %s", sql)
                .isFalse();
    }

    /** A trace through the real ingestion path, with the fields podam cannot fill sensibly cleared. */
    private Trace createTrace() {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null)
                .build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        return trace;
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
     * Seeds one row through the table's real column definitions. Only the identity columns are supplied; {@code id_at}
     * is {@code MATERIALIZED}, so ClickHouse derives it — and, on this table, wraps it — exactly as it would for a row
     * the ingestion path wrote. That wrap is the thing under test, so it has to come from ClickHouse, not from a value
     * this test computed and inserted.
     */
    private void insertRawTrace(UUID projectId, UUID id) {
        execute(INSERT_RAW_TRACE, statement -> statement
                .bind("workspace_id", WORKSPACE_ID)
                .bind("project_id", projectId.toString())
                .bind("id", id.toString()));
    }

    /** The real project id the ingestion path minted, read back off the created trace. */
    private UUID projectIdOf(Trace trace) {
        return traceResourceClient
                .getTraces(trace.projectName(), null, API_KEY, WORKSPACE_NAME, List.of(), List.of(), 100, Map.of())
                .content().stream()
                .filter(found -> found.id().equals(trace.id()))
                .map(Trace::projectId)
                .findFirst()
                .orElseThrow();
    }

    /** {@code "1"} while a live (non-lightweight-deleted) row exists for the id, {@code "0"} once it is gone. */
    private String liveRowCount(UUID projectId, UUID id) {
        return queryOneString(LIVE_ROW_COUNT, statement -> statement
                .bind("workspace_id", WORKSPACE_ID)
                .bind("project_id", projectId.toString())
                .bind("id", id.toString()));
    }

    private String legacySchema() {
        return queryOneString(LEGACY_SCHEMA, _ -> {
        });
    }

    /**
     * The SQL of the trace delete that carried {@code traceId}. Filtered by {@code log_comment} <b>and</b> the id, so a
     * neighbouring suite's delete in this shared container cannot be picked up instead — the id narrows it to this test.
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
