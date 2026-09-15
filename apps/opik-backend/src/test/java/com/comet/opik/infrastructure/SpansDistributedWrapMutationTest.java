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
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
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
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@code SpanDAO}'s mutation paths against the post-wrap topology, where {@code spans} is a
 * {@code Distributed} table over the {@code spans_local} shard (OPIK-7799) — the spans counterpart of
 * {@link TracesDistributedWrapMutationTest}. A {@code Distributed} table supports {@code SELECT} and {@code INSERT} but
 * <b>not</b> mutations, so before the retarget every span delete would fail the instant the wrap was applied. With
 * {@code databaseAnalyticsDataModel.spansDistributedWrapEnabled=true} the DAO routes its deletes to {@code spans_local}
 * while reads and inserts keep flowing through the Distributed {@code spans}.
 *
 * <p>The suite is deliberately black-box for the cascade: it drives the public span and trace APIs (create / list /
 * delete-trace), so a cascade delete that still hit the Distributed {@code spans} would leave the spans in place.
 * Two internal touches are justified: the wrap has no public API, so {@link #beforeAll} builds it in raw SQL mirroring
 * the traces cutover's wrap block; and retention sweeps have no public endpoint (retention is disabled everywhere and
 * runs only from the internal {@code RetentionCatchUpJob}), so those two DAO methods are invoked directly.
 * {@link #distributedSpansRejectsDirectMutation} is the guard that keeps the positive tests honest — it proves
 * {@code spans} really is a mutation-rejecting {@code Distributed} table, so a green delete could only have run against
 * {@code spans_local}.
 *
 * <p><b>Awaitility on the cascade only.</b> Spans have no standalone delete endpoint; the only cascade path is the
 * {@code TracesDeleted} event, dispatched on the {@code AsyncEventBus}, so the span rows disappear after the trace
 * delete returns. The two retention sweeps are direct DAO calls carrying {@code lightweight_deletes_sync = 1}, so they
 * are asserted synchronously.
 *
 * <p>Dedicated, non-reused ClickHouse and ZooKeeper containers are required because the wrap destructively renames the
 * live {@code spans} table; a reused container would corrupt other suites and reruns.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpansDistributedWrapMutationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    // Dedicated, non-reused ClickHouse + ZooKeeper on their own network: the wrap destructively renames `spans`, so a
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
                                new CustomConfig("databaseAnalyticsDataModel.spansDistributedWrapEnabled", "true")))
                        .build());
    }

    private SpanResourceClient spanResourceClient;
    private TraceResourceClient traceResourceClient;
    private TransactionTemplateAsync template;
    private SpanDAO spanDAO;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template, @Jit SpanDAO spanDAO) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.template = template;
        this.spanDAO = spanDAO;
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
    void traceDeleteCascadeRemovesSpansThroughTheLocalShardUnderTheDistributedWrap() {
        var trace = seedTraceWithSpanAt(ID_GENERATOR.generateId());

        // The live user path: DELETE /traces/{id} resolves the owning project and cascades to its spans. Against the
        // Distributed `spans` the cascade delete fails (see the guard below) and the spans would survive.
        traceResourceClient.deleteTrace(trace.id(), WORKSPACE_NAME, API_KEY);

        awaitNoSpansOn(trace);
    }

    @Test
    void deleteForRetentionRunsAgainstTheLocalShard() {
        var window = RetentionWindow.aroundNow();
        var trace = seedTraceWithSpanAt(window.middleId());

        spanDAO.deleteForRetention(List.of(WORKSPACE_ID), window.cutoffId(), window.lowerBound()).block();

        assertThat(spanIdsOf(trace)).isEmpty();
    }

    @Test
    void deleteForRetentionBoundedRunsAgainstTheLocalShard() {
        var window = RetentionWindow.aroundNow();
        var trace = seedTraceWithSpanAt(window.middleId());

        spanDAO.deleteForRetentionBounded(Map.of(WORKSPACE_ID, window.lowerBound()), window.cutoffId(),
                window.lowerBound()).block();

        assertThat(spanIdsOf(trace)).isEmpty();
    }

    @Test
    void distributedSpansRejectsDirectMutation() {
        // Guard: a lightweight DELETE against the Distributed `spans` is what the DAO used to issue and what the wrap
        // rejects (ClickHouse code 36 BAD_ARGUMENTS). Asserting the specific rejection — not merely that something
        // threw — is what proves `spans` really is a mutation-rejecting Distributed table, so the positive deletes
        // above could only have run against `spans_local`.
        var id = ID_GENERATOR.generateId();
        assertThatThrownBy(() -> execute("DELETE FROM spans WHERE workspace_id = :workspace_id AND id = :id",
                statement -> statement.bind("workspace_id", WORKSPACE_ID).bind("id", id)))
                .hasMessageContaining("Code: 36")
                .hasMessageContaining("DELETE query is not supported for table");
    }

    /**
     * A trace with every trace-table column populated. Only the span-derived aggregates podam would otherwise
     * fabricate ({@code feedbackScores}, {@code usage}) are nulled, since they are not columns of the {@code traces}
     * table.
     */
    private Trace.TraceBuilder newTrace() {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null);
    }

    /** A span in the given project and trace; {@code feedbackScores} is derived rather than a column, so it is nulled. */
    private Span.SpanBuilder newSpan(String projectName, UUID traceId) {
        return factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .traceId(traceId)
                .feedbackScores(null);
    }

    /**
     * A trace carrying exactly one span, both keyed on {@code id}. The insert and the read-back both route through
     * {@code spans}, so the assertion here is also what shows that path intact on whichever topology the suite runs.
     */
    private Trace seedTraceWithSpanAt(UUID id) {
        var trace = newTrace().id(id).build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        var span = newSpan(trace.projectName(), id).build();
        spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);
        assertThat(spanIdsOf(trace)).containsExactly(span.id());
        return trace;
    }

    private List<UUID> spanIdsOf(Trace trace) {
        return spanResourceClient
                .getByTraceIdAndProject(trace.id(), trace.projectName(), WORKSPACE_NAME, API_KEY)
                .content().stream()
                .map(Span::id)
                .toList();
    }

    /** The cascade runs on the AsyncEventBus after the trace delete returns, so its outcome is polled. */
    private void awaitNoSpansOn(Trace trace) {
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(spanIdsOf(trace)).isEmpty());
    }

    /**
     * Wraps {@code spans} as a {@code Distributed} table over the {@code spans_local} shard, mirroring the traces
     * cutover's wrap block ({@code 000003_exchange_and_wrap.sql}, also mirrored inline by
     * {@code TracesDistributedWrapMutationTest.applyDistributedWrap}): build the wrapper under a temp name, then one
     * atomic multi-target {@code RENAME} rotates the data to {@code spans_local} and the wrapper into {@code spans}.
     * The sharding key is {@code sipHash64(project_id)}, co-locating a project's spans with its traces.
     */
    private void applyDistributedWrap() {
        execute("""
                CREATE TABLE spans_dist ON CLUSTER '{cluster}' AS spans
                ENGINE = Distributed('{cluster}', '%s', 'spans_local', sipHash64(project_id))
                """.formatted(DATABASE_NAME), _ -> {
        });
        execute("""
                RENAME TABLE
                    spans TO spans_local,
                    spans_dist TO spans
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

    /**
     * A UUIDv7 id-range bracketing one seeded span's {@code trace_id} within a ±1s window, so the retention queries'
     * {@code trace_id >= lower AND trace_id < cutoff} bounds select exactly it. Derived from {@code now} rather than a
     * fixed calendar date, so it never ages. Mirrors {@code TracesDistributedWrapMutationTest.RetentionWindow}; the
     * spans sweeps carry no week predicate, since their range keys on {@code trace_id} while the partition column
     * derives from the span's own id.
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
}
