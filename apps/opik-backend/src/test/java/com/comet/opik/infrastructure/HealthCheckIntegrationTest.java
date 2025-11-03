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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class HealthCheckIntegrationTest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private String baseURI;
    private ClientSupport client;

    record HealthCheckResponse(String name, boolean healthy, boolean critical, String type) {
    }

    {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS, ZOOKEEPER_CONTAINER).join();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE,
                ClickHouseContainerUtils.DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(MYSQL.getJdbcUrl(),
                databaseAnalyticsFactory, null, REDIS.getRedisURI());
    }

    @BeforeAll
    void setUpAll(ClientSupport client) {

        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);
    }

    @Test
    void test__whenHitClickhouseHealthyCheck__thenReturnOk(ClientSupport client) {
        var response = client.target("%s/health-check?name=clickhouse".formatted(baseURI))
                .request()
                .get();

        assertEquals(200, response.getStatus());
        List<HealthCheckResponse> healthChecks = response.readEntity(new GenericType<>() {
        });

        assertThat(healthChecks).hasSize(1);
        var healthCheck = healthChecks.getFirst();

        assertResponse(healthCheck, "clickhouse");
    }

    @Test
    void test__whenHitMysqlHealthyCheck__thenReturnOk(ClientSupport client) {
        var response = client.target("%s/health-check?name=mysql".formatted(baseURI))
                .request()
                .get();

        assertEquals(200, response.getStatus());
        List<HealthCheckResponse> healthChecks = response.readEntity(new GenericType<>() {
        });

        assertThat(healthChecks).hasSize(1);
        var healthCheck = healthChecks.getFirst();

        assertResponse(healthCheck, "mysql");
    }

    private static void assertResponse(HealthCheckResponse healthCheck, String expected) {
        assertThat(healthCheck.name()).isEqualTo(expected);
        assertThat(healthCheck.type()).isEqualTo("READY");
        assertThat(healthCheck.healthy()).isTrue();
        assertThat(healthCheck.critical()).isTrue();
    }

    @Test
    void test__whenHitRedisHealthyCheck__thenReturnOk(ClientSupport client) {
        var response = client.target("%s/health-check?name=redis".formatted(baseURI))
                .request()
                .get();

        assertEquals(200, response.getStatus());
        List<HealthCheckResponse> healthChecks = response.readEntity(new GenericType<>() {
        });

        assertThat(healthChecks).hasSize(1);
        var healthCheck = healthChecks.getFirst();

        assertResponse(healthCheck, "redis");
    }

    @Test
    void test__whenHitAllHealthyCheck__thenReturnOk(ClientSupport client) {
        var response = client.target("%s/health-check?name=all".formatted(baseURI))
                .request()
                .get();

        assertEquals(200, response.getStatus());
        List<HealthCheckResponse> healthChecks = response.readEntity(new GenericType<>() {
        });

        assertThat(healthChecks).hasSize(5);

        assertThat(healthChecks).contains(
                new HealthCheckResponse("clickhouse", true, true, "READY"),
                new HealthCheckResponse("mysql", true, true, "READY"),
                new HealthCheckResponse("redis", true, true, "READY"),
                new HealthCheckResponse("db", true, true, "READY"),
                new HealthCheckResponse("deadlocks", true, true, "ALIVE"));
    }

}