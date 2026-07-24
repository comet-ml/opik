package com.comet.opik.infrastructure;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.GenericType;
import lombok.Builder;
import org.apache.hc.core5.http.HttpStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthCheckIntegrationTest {

    private static final String READY = "READY";
    private static final String ALIVE = "ALIVE";

    private static final GenericType<List<HealthCheckResponse>> HEALTH_CHECK_LIST_GENERIC_TYPE = new GenericType<>() {
    };

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS, ZOOKEEPER_CONTAINER).join();
        // The v2 Client built by DatabaseAnalyticsFactory sets setDefaultDatabase("opik"), so
        // ClickHouse rejects every query — including the probe's SELECT 1 — with UNKNOWN_DATABASE
        // until that database exists. Run the analytics changelog up-front to create it.
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);
    }

    private TestDropwizardAppExtension newApp(List<CustomConfig> customConfigs) {
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, ClickHouseContainerUtils.DATABASE_NAME);
        return TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(customConfigs)
                        .build());
    }

    /**
     * Default test-config: {@code serviceToggles.agentInsightsEnabled} off. Covers the standard
     * health check HTTP surface (per-check + aggregate {@code all}). Opik-owned checks
     * (clickhouse, mysql, redis, clickhouse-readonly-freeform-sql) are asserted individually;
     * Dropwizard-provided checks (db, deadlocks) only appear in the aggregate row.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @ExtendWith(DropwizardAppExtensionProvider.class)
    class DefaultConfig {

        @RegisterApp
        private final TestDropwizardAppExtension app = newApp(List.of());

        private ClientSupport client;
        private String baseURI;

        @BeforeAll
        void setUpAll(ClientSupport client) {
            this.client = client;
            this.baseURI = TestUtils.getBaseUrl(client);
            ClientSupportUtils.config(client);
        }

        private Stream<Arguments> healthCheckOk() {
            var clickHouseResponse = HealthCheckResponse.builder()
                    .name("clickhouse").healthy(true).critical(true).type(READY).build();
            // Toggle off in config-test → healthy without touching ClickHouse; non-critical so a
            // freeform-SQL issue never gates overall readiness.
            var clickhouseFreeformSqlResponse = HealthCheckResponse.builder()
                    .name("clickhouse-readonly-freeform-sql").healthy(true).critical(false).type(READY).build();
            // Toggle off in config-test (databaseAnalytics.clusterHealthCheckEnabled / coldStorageDiskHealthCheckEnabled)
            // → healthy without touching ClickHouse. Critical when enabled, so listed critical here.
            var clickhouseClusterResponse = HealthCheckResponse.builder()
                    .name("clickhouse-cluster").healthy(true).critical(true).type(READY).build();
            var clickhouseColdStorageDiskResponse = HealthCheckResponse.builder()
                    .name("clickhouse-cold-storage-disk").healthy(true).critical(true).type(READY).build();
            var mysqlResponse = HealthCheckResponse.builder()
                    .name("mysql").healthy(true).critical(true).type(READY).build();
            var redisResponse = HealthCheckResponse.builder()
                    .name("redis").healthy(true).critical(true).type(READY).build();
            var dbResponse = HealthCheckResponse.builder()
                    .name("db").healthy(true).critical(true).type(READY).build();
            var deadlocks = HealthCheckResponse.builder()
                    .name("deadlocks").healthy(true).critical(true).type(ALIVE).build();
            // Always-on and independent of auth: the probe leases from the live shared pool against a fixed loopback
            // target and the connection failure there is ignored, so an alive pool reports healthy.
            var sharedHttpClientResponse = HealthCheckResponse.builder()
                    .name("shared_http_client").healthy(true).critical(true).type(ALIVE).build();
            var all = List.of(
                    clickHouseResponse,
                    clickhouseFreeformSqlResponse,
                    clickhouseClusterResponse,
                    clickhouseColdStorageDiskResponse,
                    mysqlResponse,
                    redisResponse,
                    dbResponse,
                    deadlocks,
                    sharedHttpClientResponse);
            return Stream.of(
                    arguments("clickhouse", List.of(clickHouseResponse)),
                    arguments("mysql", List.of(mysqlResponse)),
                    arguments("redis", List.of(redisResponse)),
                    arguments("clickhouse-readonly-freeform-sql", List.of(clickhouseFreeformSqlResponse)),
                    arguments("clickhouse-cluster", List.of(clickhouseClusterResponse)),
                    arguments("clickhouse-cold-storage-disk", List.of(clickhouseColdStorageDiskResponse)),
                    arguments("shared_http_client", List.of(sharedHttpClientResponse)),
                    arguments("all", all));
        }

        @ParameterizedTest(name = "healthCheckOk - {0}")
        @MethodSource
        void healthCheckOk(String name, List<HealthCheckResponse> expectedResponse) {
            callHealthCheckAndAwaitAssertResponse(client, baseURI, name, expectedResponse);
        }
    }

    /**
     * {@code serviceToggles.agentInsightsEnabled} on. The production-shape Agent Insights
     * read-only user is provisioned globally on the test ClickHouse container (see
     * {@code src/test/resources/users.xml}, mirroring
     * {@code apps/opik-backend/provision_agent_insights_readonly_user.sh}), so the {@code
     * clickhouse-readonly-freeform-sql} probe actually runs against it. Without the {@code
     * newQuerySettings()} override in
     * {@link com.comet.opik.infrastructure.db.ClickHouseReadOnlyFreeFormSqlHealthCheck} the probe
     * is rejected with {@code Code: 164. DB::Exception: Cannot modify 'max_execution_time'
     * setting in readonly mode. (READONLY)}.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @ExtendWith(DropwizardAppExtensionProvider.class)
    class AgentInsightsEnabled {

        @RegisterApp
        private final TestDropwizardAppExtension app = newApp(List.of(
                new CustomConfig("serviceToggles.agentInsightsEnabled", "true")));

        private ClientSupport client;
        private String baseURI;

        @BeforeAll
        void setUpAll(ClientSupport client) {
            this.client = client;
            this.baseURI = TestUtils.getBaseUrl(client);
            ClientSupportUtils.config(client);
        }

        @Test
        void healthCheckOk() {
            var expected = HealthCheckResponse.builder()
                    .name("clickhouse-readonly-freeform-sql").healthy(true).critical(false).type(READY).build();

            callHealthCheckAndAwaitAssertResponse(
                    client, baseURI, "clickhouse-readonly-freeform-sql", List.of(expected));
        }
    }

    /**
     * Both existence toggles on, so each probe runs its real {@code count()} query against the test
     * ClickHouse — exercising the end-to-end SQL path the unit tests mock out. The container's {@code
     * clickhouse.xml} defines a {@code cluster} remote_server (mirroring the deploy macros), so the
     * cluster probe finds it and reports healthy; there is no {@code cold_s3} disk, so that probe
     * reports unhealthy. Both are {@code critical: true}, so the per-check endpoint may return a
     * non-OK status while still carrying the JSON body.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @ExtendWith(DropwizardAppExtensionProvider.class)
    class ExistenceChecksEnabled {

        @RegisterApp
        private final TestDropwizardAppExtension app = newApp(List.of(
                new CustomConfig("databaseAnalytics.clusterHealthCheckEnabled", "true"),
                new CustomConfig("databaseAnalytics.coldStorageDiskHealthCheckEnabled", "true")));

        private ClientSupport client;
        private String baseURI;

        @BeforeAll
        void setUpAll(ClientSupport client) {
            this.client = client;
            this.baseURI = TestUtils.getBaseUrl(client);
            ClientSupportUtils.config(client);
        }

        private Stream<Arguments> healthCheckRunsAgainstClickHouse() {
            // Cluster 'cluster' is defined in the test container's clickhouse.xml → present → healthy.
            // Disk 'cold_s3' is not configured on the test container → absent → unhealthy.
            return Stream.of(
                    arguments("clickhouse-cluster", true),
                    arguments("clickhouse-cold-storage-disk", false));
        }

        @ParameterizedTest(name = "healthCheckRunsAgainstClickHouse - {0} -> healthy={1}")
        @MethodSource
        void healthCheckRunsAgainstClickHouse(String name, boolean healthy) {
            var expected = HealthCheckResponse.builder()
                    .name(name).healthy(healthy).critical(true).type(READY).build();

            Awaitility.await()
                    .atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertResponse(readHealthCheck(client, baseURI, name), List.of(expected)));
        }
    }

    @Builder
    private record HealthCheckResponse(String name, boolean healthy, boolean critical, String type) {
    }

    /**
     * Polls {@code /health-check?name=...} until the response matches {@code expected}. Required
     * because Dropwizard's health endpoint returns cached state from the periodic scheduler; the
     * test ClickHouse/MySQL/Redis containers (and the read-only ClickHouse user under
     * Agent Insights) take a probe cycle or two after app start to be reflected. The aggressive
     * schedule in {@code config-test.yml} (100 ms interval, single-attempt thresholds) keeps the
     * window short, so a broken probe never satisfies the assertion before the timeout.
     */
    private void callHealthCheckAndAwaitAssertResponse(ClientSupport client, String baseURI, String name,
            List<HealthCheckResponse> expected) {
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var actual = callHealthCheckAndAssertOk(client, baseURI, name);
                    assertResponse(actual, expected);
                });
    }

    private List<HealthCheckResponse> callHealthCheckAndAssertOk(ClientSupport client, String baseURI, String name) {
        try (var response = client.target("%s/health-check?name=%s".formatted(baseURI, name))
                .request()
                .get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(HEALTH_CHECK_LIST_GENERIC_TYPE);
        }
    }

    /**
     * Reads the health check body without asserting the HTTP status: an unhealthy {@code critical}
     * check makes the endpoint return a non-OK status while still carrying the JSON results.
     */
    private List<HealthCheckResponse> readHealthCheck(ClientSupport client, String baseURI, String name) {
        try (var response = client.target("%s/health-check?name=%s".formatted(baseURI, name))
                .request()
                .get()) {
            return response.readEntity(HEALTH_CHECK_LIST_GENERIC_TYPE);
        }
    }

    private void assertResponse(List<HealthCheckResponse> actual, List<HealthCheckResponse> expected) {
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
