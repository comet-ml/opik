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

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The other side of {@code TracesPartitionPruningMutationTest}: the trace delete with
 * {@code databaseAnalyticsDataModel.tracesWeeklyPartitionPruningEnabled} <b>off</b> — its default — against the
 * <b>legacy</b> {@code traces}. That is not an exotic topology; it is the state every deployment is in today, and the
 * state a stage B/C rollback returns to.
 *
 * <p>What it guards is a <b>false-flag regression</b>: the partition predicate emitted while the flag is off, or any
 * other {@code id_at} narrowing creeping into the unbounded form. Legacy {@code traces} has no {@code PARTITION BY} at
 * all and declares {@code id_at} as a 32-bit {@code DateTime} that overflows past 2106 (migrations 000001 and 000091),
 * so a predicate here prunes nothing and can silently exclude rows.
 *
 * <p>Nothing else catches it. Every other trace-delete suite runs in exactly this state and asserts only that rows go
 * away — which they do for a <b>recent</b> id, because the legacy {@code id_at} is accurate for one. The damage shows
 * only on a far-future id (litellm <a href="https://github.com/BerriAI/litellm/issues/31294">BerriAI/litellm#31294</a>
 * mints ~2201): the 32-bit column stores a wrapped recent timestamp, a derived partition cannot match it, and the
 * delete reports success having matched <b>zero</b> rows. No existing test has such a row, because ingestion rejects
 * far-future ids by design ({@code IdGenerator.validateId}) — so this seeds one in raw SQL and deletes it through
 * {@link TraceDAO#delete}, which is also the only way to reach that arm.
 *
 * <p>Shared, reusable containers: unlike the post-EXCHANGE suite this changes no topology, so there is nothing here
 * that could corrupt another suite or a rerun.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesPruningDisabledMutationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    /** A UUIDv7 carrying id_at 2200-01-01 — the litellm shape, and the id the legacy 32-bit column wraps. */
    private static final UUID FAR_FUTURE_ID = UUID.fromString("0699eb8a-59dd-7215-8000-03b8d2a8d5e2");

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
    @DisplayName("with pruning off, a far-future row on the legacy table is still deleted, unbounded")
    void farFutureRowOnLegacyTableIsStillDeleted() {
        // The project and its id come from the real ingestion path - create a trace through the endpoint, then read the
        // project id back off it - so the delete runs against a project that exists and a genuine UUIDv7 project id,
        // not a fabricated one.
        var seedTrace = factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null)
                .build();
        traceResourceClient.createTrace(seedTrace, API_KEY, WORKSPACE_NAME);
        var projectId = projectIdOf(seedTrace);
        assertThat(projectId.version()).as("the project id is a real UUIDv7, as the backend mints them").isEqualTo(7);

        // Only the far-future row is raw, because ingestion rejects it by design (24h window). A recent id would prove
        // nothing here anyway: the legacy id_at is accurate for one, so even a wrongly-emitted predicate would match it
        // and the row would still go.
        insertRawTrace(projectId, FAR_FUTURE_ID);
        assertThat(liveRowCount(projectId, FAR_FUTURE_ID)).as("the far-future row is seeded").isEqualTo("1");

        delete(Set.of(Pair.of(projectId, FAR_FUTURE_ID)));

        assertThat(liveRowCount(projectId, FAR_FUTURE_ID))
                .as("it is deleted - a partition predicate here would have matched nothing and reported success")
                .isEqualTo("0");

        var sql = lastTraceDeleteSql(FAR_FUTURE_ID);
        assertThat(sql)
                .as("no partition predicate is emitted while the flag is off")
                .doesNotContain("toYYYYMMDD", "toDayOfWeek", "toIntervalDay");
        assertThat(sql)
                .as("and no id_at narrowing of any kind - toMonday, a range, or otherwise")
                .doesNotContain("id_at");
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
     * the ingestion path wrote.
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
