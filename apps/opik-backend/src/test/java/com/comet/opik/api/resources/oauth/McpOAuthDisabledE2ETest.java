package com.comet.opik.api.resources.oauth;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.domain.mcpoauth.McpOAuthScrubJob;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
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

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP_OAUTH_ENABLED=false (the default) must be a real kill switch: every OAuth surface is
 * unreachable and opik_at_ bearer tokens are ignored by the auth filter instead of being
 * validated against the token store.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@DisplayName("MCP OAuth Disabled E2E Test")
class McpOAuthDisabledE2ETest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS, ZOOKEEPER_CONTAINER).join();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE,
                ClickHouseContainerUtils.DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(MYSQL.getJdbcUrl(),
                databaseAnalyticsFactory, null, REDIS.getRedisURI());
    }

    private String baseURI;
    private ClientSupport client;
    private Injector injector;

    @BeforeAll
    void beforeAll(ClientSupport client, Injector injector) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        this.injector = injector;
        ClientSupportUtils.config(client);
    }

    @Test
    @DisplayName("all OAuth endpoints return 404 when disabled")
    void oauthEndpointsNotFound() {
        try (var response = client.target(baseURI + "/.well-known/oauth-authorization-server")
                .request().get()) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
        }
        try (var response = client.target(baseURI + "/oauth/register")
                .request()
                .post(Entity.json(Map.of("client_name", "x", "redirect_uris", Set.of("http://127.0.0.1/cb"))))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
        }
        try (var response = client.target(baseURI + "/oauth/token")
                .request()
                .post(Entity.form(new Form().param("grant_type", "authorization_code")))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
        }
        try (var response = client.target(baseURI + "/opik/auth-oauth")
                .request()
                .header(HttpHeaders.AUTHORIZATION, "Bearer opik_at_anything")
                .post(Entity.json(""))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
        }
    }

    @Test
    @DisplayName("auth filter ignores opik_at_ bearer tokens when disabled (no token-store validation)")
    void authFilterIgnoresOAuthBearerWhenDisabled() {
        // OSS mode with OAuth disabled: the request must fall through to the regular (no-op) auth
        // path and succeed. Without the kill-switch gate, the filter would try to validate the
        // token against the store and reject the request with 401.
        try (var response = client.target(baseURI + "/v1/private/projects")
                .request()
                .header(HttpHeaders.AUTHORIZATION, "Bearer opik_at_not-validated-when-disabled")
                .get()) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }
    }

    @Test
    @DisplayName("the data-deleting scrub job is not scheduled when disabled")
    void scrubJobNotScheduledWhenDisabled() {
        // GuiceJobManager schedules every Job-typed Guice binding it finds. The kill switch for this
        // destructive job rests on disableExtensions(McpOAuthScrubJob.class) removing the binding so the
        // manager never sees it; assert the binding is genuinely gone rather than trusting that contract.
        assertThat(hasScrubJobBinding(injector)).isFalse();
    }

    static boolean hasScrubJobBinding(Injector injector) {
        return injector.getAllBindings().keySet().stream()
                .anyMatch(key -> key.getTypeLiteral().getRawType().equals(McpOAuthScrubJob.class));
    }
}
