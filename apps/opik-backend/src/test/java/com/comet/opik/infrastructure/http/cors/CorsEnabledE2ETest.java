package com.comet.opik.infrastructure.http.cors;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.net.HttpHeaders;
import com.redis.testcontainers.RedisContainer;
import org.eclipse.jetty.http.HttpMethod;
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
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CORS headers logic test when CORS config is enabled")
@ExtendWith(DropwizardAppExtensionProvider.class)
class CorsEnabledE2ETest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

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
                        .corsEnabled(true)
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
    @DisplayName("Should contain CORS headers for GET request")
    void testCorsHeadersForGet() {
        var response = client.target("%s/is-alive/ping".formatted(baseURI))
                .request().header(HttpHeaders.ORIGIN, "localhost")
                .get();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaders()).containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);

        var exposeHeaders = response.getHeaderString("Access-Control-Expose-Headers");
        assertThat(exposeHeaders).isNotNull().contains(HttpHeaders.LOCATION);
    }

    @Test
    @DisplayName("Should contain CORS headers for OPTIONS request containing the comet workspace header")
    void testCorsHeadersForRequestHeader() {
        try (var response = client.target("%s/v1/private/projects".formatted(baseURI))
                .request()
                .header(HttpHeaders.ORIGIN, "localhost")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, RequestContext.WORKSPACE_HEADER)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.toString())
                .options()) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeaders()).containsKey(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        }
    }
}
