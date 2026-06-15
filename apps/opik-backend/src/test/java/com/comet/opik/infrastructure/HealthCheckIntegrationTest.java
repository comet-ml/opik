package com.comet.opik.infrastructure;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.GenericType;
import lombok.Builder;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class HealthCheckIntegrationTest {

    private static final String READY = "READY";
    private static final String ALIVE = "ALIVE";

    private static final GenericType<List<HealthCheckResponse>> HEALTH_CHECK_LIST_GENERIC_TYPE = new GenericType<>() {
    };

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    @RegisterApp
    private final TestDropwizardAppExtension app;

    private String baseURI;
    private ClientSupport client;

    {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS, ZOOKEEPER_CONTAINER).join();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, ClickHouseContainerUtils.DATABASE_NAME);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, null, REDIS.getRedisURI());
    }

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        ClientSupportUtils.config(client);
    }

    /**
     * Opik-owned checks (clickhouse, mysql, redis, clickhouse-readonly-freeform-sql) are asserted
     * individually; Dropwizard-provided checks (db, deadlocks) only appear in the aggregate
     * {@code all} row.
     */
    private Stream<Arguments> healthCheckOk() {
        var clickHouseResponse = HealthCheckResponse.builder()
                .name("clickhouse").healthy(true).critical(true).type(READY).build();
        // Toggle off in config-test → healthy without touching ClickHouse; non-critical so a
        // freeform-SQL issue never gates overall readiness.
        var clickhouseFreeformSqlResponse = HealthCheckResponse.builder()
                .name("clickhouse-readonly-freeform-sql").healthy(true).critical(false).type(READY).build();
        var mysqlResponse = HealthCheckResponse.builder()
                .name("mysql").healthy(true).critical(true).type(READY).build();
        var redisResponse = HealthCheckResponse.builder()
                .name("redis").healthy(true).critical(true).type(READY).build();
        var dbResponse = HealthCheckResponse.builder()
                .name("db").healthy(true).critical(true).type(READY).build();
        var deadlocks = HealthCheckResponse.builder()
                .name("deadlocks").healthy(true).critical(true).type(ALIVE).build();
        var all = List.of(
                clickHouseResponse, clickhouseFreeformSqlResponse, mysqlResponse, redisResponse, dbResponse, deadlocks);
        return Stream.of(
                arguments("clickhouse", List.of(clickHouseResponse)),
                arguments("mysql", List.of(mysqlResponse)),
                arguments("redis", List.of(redisResponse)),
                arguments("clickhouse-readonly-freeform-sql", List.of(clickhouseFreeformSqlResponse)),
                arguments("all", all));
    }

    @ParameterizedTest(name = "healthCheckOk - {0}")
    @MethodSource
    void healthCheckOk(String name, List<HealthCheckResponse> expectedResponse) {
        var actualResponse = callHealthCheckAndAssertOk(name);

        assertResponse(actualResponse, expectedResponse);
    }

    @Builder
    private record HealthCheckResponse(String name, boolean healthy, boolean critical, String type) {
    }

    private List<HealthCheckResponse> callHealthCheckAndAssertOk(String name) {
        try (var response = client.target("%s/health-check?name=%s".formatted(baseURI, name))
                .request()
                .get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(HEALTH_CHECK_LIST_GENERIC_TYPE);
        }
    }

    private void assertResponse(List<HealthCheckResponse> actual, List<HealthCheckResponse> expected) {
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
