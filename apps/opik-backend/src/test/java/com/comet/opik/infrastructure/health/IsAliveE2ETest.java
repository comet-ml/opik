package com.comet.opik.infrastructure.health;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.redis.testcontainers.RedisContainer;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.io.BufferedReader;
import java.io.FileReader;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Is Alive Resource Test")
class IsAliveE2ETest {

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    static {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS).join();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .build());
    }

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void setUpAll(ClientSupport client) {

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);
    }

    @Test
    @DisplayName("Should return 200 OK")
    void testIsAlive() {
        var response = client.target("%s/is-alive/ping".formatted(baseURI))
                .request()
                .get();

        Assertions.assertEquals(200, response.getStatus());
        var health = response.readEntity(IsAliveResource.IsAliveResponse.class);

        Assertions.assertTrue(health.healthy());
    }

    @Test
    @DisplayName("Should return concrete version when configuration has 'latest'")
    void testGetVersion() {
        var response = client.target("%s/is-alive/ver".formatted(baseURI))
                .request()
                .get();

        Assertions.assertEquals(200, response.getStatus());
        var versionResponse = response.readEntity(IsAliveResource.VersionResponse.class);

        Assertions.assertEquals(getConcreteVersion(), versionResponse.version());
    }

    @SneakyThrows
    private String getConcreteVersion() {
        String versionFile = "../../version.txt";

        try (BufferedReader br = new BufferedReader(new FileReader(versionFile))) {
            return br.readLine();
        }
    }
}
