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
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Statement;
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
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
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@code TraceDAO}'s mutation paths against the post-wrap topology, where {@code traces} is a
 * {@code Distributed} table over the {@code traces_local} shard (OPIK-7455). A {@code Distributed} table supports
 * {@code SELECT} and {@code INSERT} but <b>not</b> mutations, so before the retarget every trace delete returned 500
 * the instant the wrap was applied. With {@code databaseAnalyticsDataModel.tracesDistributedWrapEnabled=true} the DAO
 * routes its deletes to {@code traces_local} while reads and inserts keep flowing through the Distributed {@code traces}.
 *
 * <p>The suite is deliberately black-box: it drives the public trace API (create / list / delete) so a delete that
 * still hit the Distributed {@code traces} would surface as a 500 from the API, and the outcome is read back through
 * the same public list endpoint. Two internal touches are justified: the wrap has no public API, so {@link #beforeAll}
 * builds it in raw SQL identical to the {@code 000003_exchange_and_wrap.sql} wrap block; and retention sweeps have no
 * public endpoint (retention is disabled everywhere and runs only from the internal {@code RetentionCatchUpJob}), so
 * those two DAO methods are invoked directly. {@link #distributedTracesRejectsDirectMutation} is the guard that keeps
 * the positive tests honest — it proves {@code traces} really is a mutation-rejecting {@code Distributed} table, so a
 * green delete could only have run against {@code traces_local}.
 *
 * <p>Dedicated, non-reused ClickHouse and ZooKeeper containers are required because the wrap destructively renames the
 * live {@code traces} table; a reused container would corrupt other suites and reruns. No Awaitility: the trace
 * create/delete API is synchronous with respect to trace reads (an INSERT to a single local shard is direct, not
 * spooled), as the existing trace-delete suites rely on too.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesDistributedWrapMutationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    // Dedicated, non-reused ClickHouse + ZooKeeper on their own network: the wrap destructively renames `traces`, so a
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
                clickHouseContainer, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(List.of(
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
        applyDistributedWrap();
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    @Test
    void deleteByIdRemovesTraceThroughTheLocalShardUnderTheDistributedWrap() {
        var trace = newTrace().build();

        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        // Insert and read both routed through the Distributed `traces`.
        assertThat(getTraceIds(trace.projectName())).contains(trace.id());

        // Delete-by-id via the live user path. Against the Distributed `traces` the mutation 500s (see the guard
        // below), so a NO_CONTENT here means it ran against `traces_local`.
        traceResourceClient.deleteTrace(trace.id(), WORKSPACE_NAME, API_KEY);

        assertThat(getTraceIds(trace.projectName())).doesNotContain(trace.id());
    }

    @Test
    void deleteForRetentionRunsAgainstTheLocalShard() {
        var window = RetentionWindow.aroundNow();
        var trace = newTrace().id(window.middleId()).build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        assertThat(getTraceIds(trace.projectName())).contains(trace.id());

        traceDAO.deleteForRetention(List.of(WORKSPACE_ID), window.cutoffId(), window.lowerBound()).block();

        assertThat(getTraceIds(trace.projectName())).doesNotContain(trace.id());
    }

    @Test
    void deleteForRetentionBoundedRunsAgainstTheLocalShard() {
        var window = RetentionWindow.aroundNow();
        var trace = newTrace().id(window.middleId()).build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        assertThat(getTraceIds(trace.projectName())).contains(trace.id());

        traceDAO.deleteForRetentionBounded(Map.of(WORKSPACE_ID, window.lowerBound()), window.cutoffId(),
                window.lowerBound()).block();

        assertThat(getTraceIds(trace.projectName())).doesNotContain(trace.id());
    }

    @Test
    void distributedTracesRejectsDirectMutation() {
        // Guard: a lightweight DELETE against the Distributed `traces` is what the DAO used to issue and what the wrap
        // rejects (ClickHouse code 36 BAD_ARGUMENTS). Asserting the specific rejection — not merely that something
        // threw — is what proves `traces` really is a mutation-rejecting Distributed table, so the positive deletes
        // above could only have run against `traces_local`.
        var id = ID_GENERATOR.generateId();
        assertThatThrownBy(() -> execute("DELETE FROM traces WHERE workspace_id = :workspace_id AND id = :id",
                statement -> statement.bind("workspace_id", WORKSPACE_ID).bind("id", id)))
                .hasMessageContaining("DELETE query is not supported for table");
    }

    /**
     * A UUIDv7 id-range bracketing one seeded trace within a single UTC week, so the retention queries'
     * {@code id >= lower AND id < cutoff} and {@code toMonday(id_at)} bounds both select exactly it. Derived from
     * {@code now} (not a fixed calendar date, so it never ages) and only ±1s wide; {@code middleId} always sits between
     * the bounds, so the seed matches even across a Monday boundary.
     */
    @Builder(toBuilder = true)
    private record RetentionWindow(UUID lowerBound, UUID middleId, UUID cutoffId) {
        private static RetentionWindow aroundNow() {
            var now = Instant.now();
            return RetentionWindow.builder()
                    .lowerBound(ID_GENERATOR.generateId(now.minusSeconds(1)))
                    .middleId(ID_GENERATOR.generateId(now))
                    .cutoffId(ID_GENERATOR.generateId(now.plusSeconds(1)))
                    .build();
        }
    }

    /**
     * A trace with every trace-table column populated. Only the span-derived aggregates podam would otherwise
     * fabricate ({@code feedbackScores}, {@code usage}) are nulled, since they are not columns of the {@code traces}
     * table and would only add noise.
     */
    private Trace.TraceBuilder newTrace() {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null);
    }

    private List<UUID> getTraceIds(String projectName) {
        return traceResourceClient
                .getTraces(projectName, null, API_KEY, WORKSPACE_NAME, List.of(), List.of(), 100, Map.of())
                .content().stream()
                .map(Trace::id)
                .toList();
    }

    /**
     * Wraps {@code traces} as a {@code Distributed} table over the {@code traces_local} shard. The statements are kept
     * identical to the wrap block of {@code 000003_exchange_and_wrap.sql} (the cutover's source of truth, also mirrored
     * inline by {@code TracesLocalV2CutoverTest.wrapInDistributed}): build the wrapper under a temp name, then one
     * atomic multi-target {@code RENAME} rotates the data to {@code traces_local} and the wrapper into {@code traces}.
     * Only the resulting topology matters to this suite, so — as with the cutover gate test — the copies are kept in
     * step by eye rather than shared, avoiding a parser for two statements.
     */
    private void applyDistributedWrap() {
        execute("""
                CREATE TABLE traces_dist ON CLUSTER '{cluster}' AS traces
                ENGINE = Distributed('{cluster}', '%s', 'traces_local', sipHash64(project_id))
                """.formatted(DATABASE_NAME), _ -> {
        });
        execute("""
                RENAME TABLE
                    traces TO traces_local,
                    traces_dist TO traces
                    ON CLUSTER '{cluster}'
                """, _ -> {
        });
    }

    private void execute(String sql, Consumer<Statement> binder) {
        template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute()).flatMap(result -> Mono.from(result.getRowsUpdated()));
        }).block();
    }
}
