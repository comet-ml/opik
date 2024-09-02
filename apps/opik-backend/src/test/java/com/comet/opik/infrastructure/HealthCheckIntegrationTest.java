package com.comet.opik.infrastructure;

import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.GenericType;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(parallel = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthCheckIntegrationTest extends AbstractHealthCheckContainerBaseTest {

    record HealthCheckResponse(String name, boolean healthy, boolean critical, String type) {
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractHealthCheckContainerBaseTest {

    protected static final String USER = UUID.randomUUID().toString();
    protected static final String API_KEY = UUID.randomUUID().toString();
    protected static final String WORKSPACE_ID = UUID.randomUUID().toString();
    protected static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    protected static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    protected static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    protected static final TestDropwizardAppExtension app;

    protected static final WireMockUtils.WireMockRuntime wireMock;

    static {
        MYSQL.start();
        REDIS.start();
        CLICKHOUSE.start();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE,
                ClickHouseContainerUtils.DATABASE_NAME);

        wireMock = WireMockUtils.startWireMock();

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(MYSQL.getJdbcUrl(),
                databaseAnalyticsFactory,
                wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    protected String baseURI;
    protected ClientSupport client;

    @BeforeAll
    protected void setUpAll(ClientSupport client, Jdbi jdbi) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);
    }

    @AfterAll
    protected void tearDownAll() {
        wireMock.server().stop();
    }

    protected void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    protected void mockSessionCookieTargetWorkspace(String sessionToken, String workspaceName,
            String workspaceId) {
        AuthTestUtils.mockSessionCookieTargetWorkspace(wireMock.server(), sessionToken, workspaceName, workspaceId,
                USER);
    }

}
