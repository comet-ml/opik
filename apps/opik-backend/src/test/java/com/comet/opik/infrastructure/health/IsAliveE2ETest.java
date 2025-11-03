package com.comet.opik.infrastructure.health;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Is Alive Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class IsAliveE2ETest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private static final String TEST_VERSION = "2.4.1";

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS, ZOOKEEPER_CONTAINER).join();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .metadataVersion(TEST_VERSION)
                        .build());
    }

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void setUpAll(ClientSupport client) {

        this.baseURI = TestUtils.getBaseUrl(client);
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
    @DisplayName("Should return correct version")
    void testGetVersion() {
        var response = client.target("%s/is-alive/ver".formatted(baseURI))
                .request()
                .get();

        Assertions.assertEquals(200, response.getStatus());
        var versionResponse = response.readEntity(IsAliveResource.VersionResponse.class);

        Assertions.assertEquals(TEST_VERSION, versionResponse.version());
    }
}
