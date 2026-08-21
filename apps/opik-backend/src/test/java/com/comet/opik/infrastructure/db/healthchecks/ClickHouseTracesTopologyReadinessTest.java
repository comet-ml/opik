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

/**
 * End-to-end cover for the {@code clickhouse-traces-topology} readiness assertion over a really wrapped
 * {@code traces} — the post-cutover topology, which can only be built by destructively renaming the live table.
 *
 * <p>The app boots with {@code databaseAnalyticsDataModel.tracesDistributedWrapEnabled} left at its default
 * {@code false}, so the suite walks the exact transition an operator drives: matching config (flag off over a
 * {@code ReplicatedMergeTree} {@code traces}) reports ready, the wrap is applied, and readiness then fails because the
 * flag now disagrees with the database. That direction — cut over but flag off — is the one that breaks trace deletes
 * with {@code BAD_ARGUMENTS} (36) / {@code NOT_IMPLEMENTED} (48); its mirror image (flag on, never wrapped) needs no
 * wrap and so is covered on the shared containers by
 * {@code HealthCheckIntegrationTest.TracesDistributedWrapEnabledWithoutTheWrap}.
 *
 * <p>Asserting the healthy state first is what makes the unhealthy state meaningful: it rules out a probe that is
 * simply always red. The fourth combination — flag on over the wrapped table, the intended post-cutover steady state —
 * is asserted against the same live topology through a directly constructed probe, since the flag is read once at
 * startup and a second app would mean a second set of containers for one assertion.
 *
 * <p>Dedicated, non-reused ClickHouse and ZooKeeper containers are required because the wrap destructively renames the
 * live {@code traces} table; a reused container would corrupt other suites and reruns. Redis/MySQL are only read, so
 * the shared ones are fine.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ClickHouseTracesTopologyReadinessTest {

    private static final String HEALTH_CHECK_NAME = "clickhouse-traces-topology";
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
    }

    @AfterAll
    void afterAll() {
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    /**
     * One method rather than two: the assertion is about the transition, and the wrap is irreversible, so the
     * before/after states cannot be independent tests over the same container.
     *
     * <p>The pre-wrap state is <b>read, not assumed</b>. Today the wrap is operator tooling
     * ({@code data-migrations/traces-local-v2-cutover}), outside the {@code migrations/} directory the analytics
     * changelog includes, so this suite starts on a {@code ReplicatedMergeTree} {@code traces} and there is a real
     * transition to drive. The day the wrap lands as a regular migration, it starts already wrapped — and asserting
     * "healthy first" would then fail on a correctly behaving probe. So the healthy half runs only when there is
     * something to transition from; the mismatch half, which is the point of the test, always runs.
     */
    @Test
    @Order(1)
    void readinessFlipsToUnhealthyWhenTheWrapIsAppliedWhileTheFlagIsOff() {
        if (!isWrapped()) {
            awaitHealthCheck(true);
            awaitReadinessProbe(HttpStatus.SC_OK);

            applyDistributedWrap();
        }

        awaitHealthCheck(false);
        awaitReadinessProbe(HttpStatus.SC_SERVICE_UNAVAILABLE);
    }

    /**
     * The intended post-cutover steady state: the same wrapped {@code traces}, read by a probe whose flag is on. The
     * app under test cannot supply it — {@code tracesDistributedWrapEnabled} is read once in the constructor — so the
     * probe is built directly over the app's live ClickHouse client. Ordered after
     * {@link #readinessFlipsToUnhealthyWhenTheWrapIsAppliedWhileTheFlagIsOff} so that one still gets the pristine
     * pre-wrap state to transition from — the only place in this suite where execution order carries meaning, hence
     * the explicit {@code @Order}. It does not depend on that ordering to pass, though: it wraps idempotently and then
     * asserts the topology it needs, so it is correct whichever way it is reached.
     */
    @Test
    @Order(2)
    void probeWithTheFlagOnIsHealthyOverTheWrappedTopology() {
        applyDistributedWrap();

        assertThat(tracesEngine()).isEqualTo(DISTRIBUTED_ENGINE);

        var healthCheck = new ClickHouseTracesTopologyHealthCheck(clickHouseClient, PROBE_TIMEOUT,
                DatabaseAnalyticsDataModelConfig.builder().tracesDistributedWrapEnabled(true).build());

        var actualResult = healthCheck.execute();

        assertThat(actualResult.isHealthy()).isTrue();
        assertThat(actualResult.getMessage())
                .isEqualTo("'traces' is Distributed over 'traces_local', matching "
                        + "databaseAnalyticsDataModel.tracesDistributedWrapEnabled=true");
    }

    private void awaitHealthCheck(boolean healthy) {
        var expected = HealthCheckResponse.builder()
                .name(HEALTH_CHECK_NAME).healthy(healthy).critical(true).type(READY).build();

        // Dropwizard's health endpoint serves cached state from the periodic scheduler; config-test's 100 ms
        // single-attempt schedule keeps the window short, so a probe stuck on the wrong answer never satisfies this.
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(readHealthCheck()).containsExactly(expected));
    }

    private List<HealthCheckResponse> readHealthCheck() {
        // Status is deliberately not asserted: an unhealthy critical check makes the endpoint return a non-OK status
        // while still carrying the JSON results.
        try (var response = client.target("%s/health-check?name=%s".formatted(baseURI, HEALTH_CHECK_NAME))
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
     * Wraps {@code traces} as a {@code Distributed} table over the {@code traces_local} shard. The statements are kept
     * identical to the wrap block of {@code 000003_exchange_and_wrap.sql} (the cutover's source of truth, mirrored
     * inline the same way by {@code TracesLocalV2CutoverTest.wrapInDistributed} and
     * {@code TracesDistributedWrapMutationTest.applyDistributedWrap}): build the wrapper under a temp name, then one
     * atomic multi-target {@code RENAME} rotates the data to {@code traces_local} and the wrapper into {@code traces}.
     *
     * <p>Idempotent: a {@code traces} that is already {@code Distributed} is left alone. Re-running the block over one
     * would fail outright — {@code CREATE TABLE traces_dist} on the second pass, and the {@code RENAME} would rotate a
     * wrapper on top of a wrapper — so the guard is what keeps this suite correct if the wrap ever becomes a regular
     * migration and the container arrives already cut over.
     */
    private void applyDistributedWrap() {
        if (isWrapped()) {
            return;
        }
        execute("""
                CREATE TABLE traces_dist ON CLUSTER '{cluster}' AS traces
                ENGINE = Distributed('{cluster}', '%s', 'traces_local', sipHash64(project_id))
                """.formatted(DATABASE_NAME));
        execute("""
                RENAME TABLE
                    traces TO traces_local,
                    traces_dist TO traces
                    ON CLUSTER '{cluster}'
                """);
    }

    private boolean isWrapped() {
        return DISTRIBUTED_ENGINE.equals(tracesEngine());
    }

    private String tracesEngine() {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(
                    "SELECT engine FROM system.tables WHERE database = :database AND name = 'traces'");
            statement.bind("database", DATABASE_NAME);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get("engine", String.class))));
        }).block();
    }

    private void execute(String sql) {
        template.nonTransaction(connection -> Mono.from(connection.createStatement(sql).execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated()))).block();
    }

    @Builder
    private record HealthCheckResponse(String name, boolean healthy, boolean critical, String type) {
    }
}
