package com.comet.opik.infrastructure.http.cors;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.infrastructure.http.CorsFactory;
import com.google.common.net.HttpHeaders;
import com.redis.testcontainers.RedisContainer;
import org.eclipse.jetty.http.HttpMethod;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(parallel = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CORS headers logic test when CORS config is enabled")
class CorsEnabledE2ETest {
    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    static {
        MYSQL.start();
        CLICKHOUSE.start();
        REDIS.start();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
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

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);
    }

    @Test
    @DisplayName("Should contain CORS headers for GET request")
    void testCorsHeadersForGet() {
        var response = client.target("%s/is-alive/ping".formatted(baseURI))
                .request().header("Origin", "localhost")
                .get();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaders()).containsKey("Access-Control-Allow-Origin");
    }

    @Test
    @DisplayName("Should contain CORS headers for OPTIONS request containing the comet workspace header")
    void testCorsHeadersForRequestHeader() {
        try (var response = client.target("%s/v1/private/projects".formatted(baseURI))
                .request()
                .header("Origin", "localhost")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, CorsFactory.COMET_WORKSPACE_REQUEST_HEADER)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.toString())
                .options()) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeaders()).containsKey("Access-Control-Allow-Origin");
        } catch (Exception exception) {
            Assertions.fail(exception);
        }
    }
}
