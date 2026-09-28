package com.comet.opik.infrastructure.db.healthchecks;

import com.clickhouse.client.api.Client;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.core.GenericType;
import lombok.Builder;
import org.apache.hc.core5.http.HttpStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * End-to-end cover for the {@code clickhouse-spans-topology} readiness assertion over a really wrapped {@code spans} —
 * the post-cutover topology, which can only be built by destructively renaming the live table. The spans mirror of
 * {@code ClickHouseTracesTopologyReadinessTest}.
 *
 * <p>The app boots with {@code databaseAnalyticsDataModel.spansDistributedWrapEnabled} left at its default
 * {@code false}, so the suite walks the exact transition an operator drives: matching config (flag off over a
 * {@code ReplicatedMergeTree} {@code spans}) reports ready, the wrap is applied, and readiness then fails because the
 * flag now disagrees with the database. That direction — cut over but flag off — is the one that breaks span deletes
 * with {@code BAD_ARGUMENTS} (36) / {@code NOT_IMPLEMENTED} (48); its mirror image (flag on, never wrapped) needs no
 * wrap and so is covered on the shared containers by
 * {@code HealthCheckIntegrationTest.SpansDistributedWrapEnabledWithoutTheWrap}.
 *
 * <p>Asserting the healthy state first is what makes the unhealthy state meaningful: it rules out a probe that is
 * simply always red. It is a separate test rather than a preamble, so a green run says both halves ran — the wrap is
 * irreversible, hence the ordering. The remaining flag↔topology combination — flag on over the wrapped table, the
 * intended post-cutover steady state — is asserted against the same live topology through a directly constructed
 * probe, since the flag is read once at startup and a second app would mean a second set of containers for one
 * assertion.
 *
 * <p>The traces probe is asserted alongside, on the same containers: it must stay healthy across a spans wrap, which
 * is the end-to-end form of the promise that the two probes read their own tables and the two cutovers are
 * independent.
 *
 * <p>Dedicated, non-reused ClickHouse and ZooKeeper containers are required because the wrap destructively renames the
 * live {@code spans} table; a reused container would corrupt other suites and reruns. Redis/MySQL are only read, so
 * the shared ones are fine.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ClickHouseSpansTopologyReadinessTest {

    private static final String HEALTH_CHECK_NAME = "clickhouse-spans-topology";
    private static final String TRACES_HEALTH_CHECK_NAME = "clickhouse-traces-topology";
    private static final String READY = "READY";
    private static final String DISTRIBUTED_ENGINE = "Distributed";

    /**
     * Deadline for the directly constructed probe. Not the app's configured {@code healthCheckTimeout}: this one only
     * has to be generous enough that a container round-trip cannot flake it.
     */
    private static final Duration PROBE_TIMEOUT = Duration.seconds(5);

    private static final GenericType<List<HealthCheckResponse>> HEALTH_CHECK_LIST_GENERIC_TYPE = new GenericType<>() {
    };

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeperContainer);
    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer).join();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .build());
    }

    private ClientSupport client;
    private String baseURI;
    private TransactionTemplateAsync template;
    private Client clickHouseClient;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template, Client clickHouseClient) {
        this.client = clientSupport;
        this.baseURI = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        this.template = template;
        this.clickHouseClient = clickHouseClient;
        // applyDistributedWrap's DDL spells the database out, because SQL must not be assembled with Java string
        // operations and ClickHouse cannot bind an identifier inside a Distributed() engine argument. This keeps that
        // literal honest if the shared test database name ever changes.
        assertThat(DATABASE_NAME).as("the wrap DDL hard-codes the database name").isEqualTo("opik");
    }

    @AfterAll
    void afterAll() {
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    /**
     * The control that makes the mismatch below meaningful: matching configuration — the flag off over the
     * {@code ReplicatedMergeTree} {@code spans} the migrations leave — has to report ready, or a probe that is simply
     * always red would satisfy the next test.
     *
     * <p>Only meaningful while there is something to transition from. Today the wrap is operator tooling, outside the
     * {@code migrations/} directory the analytics changelog includes, so the container starts unwrapped. The day the
     * wrap lands as a regular migration it starts wrapped, and this premise has legitimately gone away — skip with the
     * reason stated rather than fail on a correctly behaving probe, and rather than hide the lost coverage behind a
     * condition that reads as a pass. {@code HealthCheckIntegrationTest.DefaultConfig} asserts the same matching-config
     * case on shared containers, so the control survives the skip.
     */
    @Test
    @Order(1)
    void reportsReadyWhileTheFlagMatchesTheUnwrappedTopology() {
        assumeThat(spansEngine())
                .as("pre-wrap estate: `spans` is still a MergeTree, so there is a transition to drive")
                .isNotEqualTo(DISTRIBUTED_ENGINE);

        awaitHealthCheck(HEALTH_CHECK_NAME, true);
        awaitReadinessProbe(HttpStatus.SC_OK);
    }

    /**
     * The point of the suite: cut over but flag off, the direction that breaks span deletes with
     * {@code BAD_ARGUMENTS} (36) / {@code NOT_IMPLEMENTED} (48). Applies the wrap itself, idempotently, so it asserts
     * the same thing whether or not the test above ran; the ordering exists only to leave that one the pristine
     * pre-wrap state.
     */
    @Test
    @Order(2)
    void failsReadinessOnceTheWrapIsAppliedWhileTheFlagIsOff() {
        applyDistributedWrap();

        awaitHealthCheck(HEALTH_CHECK_NAME, false);
        awaitReadinessProbe(HttpStatus.SC_SERVICE_UNAVAILABLE);
    }

    /**
     * The spans wrap must not disturb the traces probe: the two cutovers are independent, and an estate wrapped on
     * spans but not on traces is a legitimate steady state. Runs after the wrap is applied so it asserts over the
     * mixed topology, which is the only state where the two probes could be confused for one another.
     */
    @Test
    @Order(3)
    void theTracesProbeStaysHealthyAcrossTheSpansWrap() {
        applyDistributedWrap();

        awaitHealthCheck(TRACES_HEALTH_CHECK_NAME, true);
    }

    /**
     * The intended post-cutover steady state: the same wrapped {@code spans}, read by a probe whose flag is on. The
     * app under test cannot supply it — {@code spansDistributedWrapEnabled} is read once in the constructor — so the
     * probe is built directly over the app's live ClickHouse client. Like every test here but the first, it wraps
     * idempotently and then asserts the topology it needs, so it does not depend on the ordering to pass: the
     * {@code @Order} exists only to leave {@link #reportsReadyWhileTheFlagMatchesTheUnwrappedTopology} the pristine
     * pre-wrap state, which is the single point in this suite where execution order carries meaning.
     */
    @Test
    @Order(4)
    void probeWithTheFlagOnIsHealthyOverTheWrappedTopology() {
        applyDistributedWrap();

        assertThat(spansEngine()).isEqualTo(DISTRIBUTED_ENGINE);

        var healthCheck = new ClickHouseSpansTopologyHealthCheck(clickHouseClient, PROBE_TIMEOUT,
                DatabaseAnalyticsDataModelConfig.builder().spansDistributedWrapEnabled(true).build());

        var actualResult = healthCheck.execute();

        assertThat(actualResult.isHealthy()).isTrue();
        assertThat(actualResult.getMessage())
                .isEqualTo("'spans' is Distributed over 'spans_local', matching "
                        + "databaseAnalyticsDataModel.spansDistributedWrapEnabled=true");
    }

    private void awaitHealthCheck(String name, boolean healthy) {
        var expected = HealthCheckResponse.builder()
                .name(name).healthy(healthy).critical(true).type(READY).build();

        // Dropwizard's health endpoint serves cached state from the periodic scheduler; config-test's 100 ms
        // single-attempt schedule keeps the window short, so a probe stuck on the wrong answer never satisfies this.
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(readHealthCheck(name)).containsExactly(expected));
    }

    private List<HealthCheckResponse> readHealthCheck(String name) {
        // Status is deliberately not asserted: an unhealthy critical check makes the endpoint return a non-OK status
        // while still carrying the JSON results.
        try (var response = client.target("%s/health-check?name=%s".formatted(baseURI, name))
                .request()
                .get()) {
            return response.readEntity(HEALTH_CHECK_LIST_GENERIC_TYPE);
        }
    }

    /**
     * The aggregate endpoint the Kubernetes readiness probe actually hits (the chart's
     * {@code component.backend.readinessProbe} is {@code /health-check?name=all&type=ready}), so this is what decides
     * whether the pod stays in rotation.
     */
    private void awaitReadinessProbe(int expectedStatus) {
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    try (var response = client.target("%s/health-check?name=all&type=ready".formatted(baseURI))
                            .request()
                            .get()) {
                        assertThat(response.getStatus()).isEqualTo(expectedStatus);
                    }
                });
    }

    /**
     * Wraps {@code spans} as a {@code Distributed} table over the {@code spans_local} shard, co-located with traces on
     * {@code sipHash64(project_id)}. The statements mirror the wrap block of the spans cutover (the same shape
     * {@code SpansDistributedWrapMutationTest.applyDistributedWrap} uses): build the wrapper under a temp name, then
     * one atomic multi-target {@code RENAME} rotates the data to {@code spans_local} and the wrapper into
     * {@code spans}.
     *
     * <p>Idempotent: a {@code spans} that is already {@code Distributed} is left alone. Re-running the block over one
     * would fail outright — {@code CREATE TABLE spans_dist} on the second pass, and the {@code RENAME} would rotate a
     * wrapper on top of a wrapper — so the guard is what keeps this suite correct if the wrap ever becomes a regular
     * migration and the container arrives already cut over.
     *
     * <p>Both statements are literal text blocks: SQL is never assembled with Java string operations, and ClickHouse
     * cannot bind an identifier inside a {@code Distributed()} engine argument, so the database name is spelled out
     * and held to {@link #DATABASE_NAME} by the assertion in {@link #beforeAll} rather than interpolated in.
     */
    private void applyDistributedWrap() {
        if (isWrapped()) {
            return;
        }
        execute("""
                CREATE TABLE spans_dist ON CLUSTER '{cluster}' AS spans
                ENGINE = Distributed('{cluster}', 'opik', 'spans_local', sipHash64(project_id))
                """);
        execute("""
                RENAME TABLE
                    spans TO spans_local,
                    spans_dist TO spans
                    ON CLUSTER '{cluster}'
                """);
    }

    private boolean isWrapped() {
        return DISTRIBUTED_ENGINE.equals(spansEngine());
    }

    private String spansEngine() {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(
                    "SELECT engine FROM system.tables WHERE database = :database AND name = 'spans'");
            statement.bind("database", DATABASE_NAME);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get("engine", String.class))));
        }).block();
    }

    private void execute(String sql) {
        template.nonTransaction(connection -> Mono.from(connection.createStatement(sql).execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated()))).block();
    }

    @Builder(toBuilder = true)
    private record HealthCheckResponse(String name, boolean healthy, boolean critical, String type) {
    }
}
