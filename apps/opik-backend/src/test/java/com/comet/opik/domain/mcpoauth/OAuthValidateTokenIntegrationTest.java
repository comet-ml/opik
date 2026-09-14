package com.comet.opik.domain.mcpoauth;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.OAuthResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
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
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_VALIDATE_TOKEN_RESOURCE_BASE_PATH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real OAuth flow (register → consent → code exchange) against the app and MySQL, then introspects the
 * minted access token through {@code POST /opik/auth-oauth}. The mocked resource test proves the wire format of a
 * hand-built DTO; this one proves the value on the wire is the persisted row's expiry, which is what a resource
 * server (opik-mcp, OPIK-8252) caches against.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@DisplayName("OAuth Validate Token Integration Test")
class OAuthValidateTokenIntegrationTest {

    private static final String REDIRECT_URI = "http://localhost:1234/callback";
    private static final String RESOURCE_URI = "http://localhost:8080/api/v1/mcp";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, CLICKHOUSE, MYSQL, ZOOKEEPER).join();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        // Local auth, so consent resolves to the default workspace without a session. A one-hour access token
        // keeps the minted token alive for the whole test.
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("mcpOAuth.enabled", "true"),
                                new CustomConfig("mcpOAuth.baseUrl", "http://localhost:8080"),
                                new CustomConfig("mcpOAuth.mcpResourceUri", RESOURCE_URI),
                                new CustomConfig("mcpOAuth.accessTokenTtl", "PT1H")))
                        .build());
    }

    private String baseURI;
    private ClientSupport client;
    private TransactionTemplate transactionTemplate;
    private OAuthResourceClient oauthClient;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, TransactionTemplate transactionTemplate) {
        this.client = clientSupport;
        this.baseURI = TestUtils.getBaseUrl(clientSupport);
        this.transactionTemplate = transactionTemplate;
        this.oauthClient = new OAuthResourceClient(clientSupport, baseURI, REDIRECT_URI, RESOURCE_URI);
    }

    @Test
    @DisplayName("introspection reports the persisted expiry of a token minted through the OAuth endpoints")
    void introspectionReportsPersistedExpiry() {
        var minted = oauthClient.mintArtifacts();
        String accessToken = minted.tokens().accessToken();
        Instant persistedExpiry = fetchTokenExpiry(McpOAuthTokenUtils.hash(accessToken));
        assertThat(persistedExpiry).isNotNull();

        try (Response response = client.target(baseURI + OAUTH_VALIDATE_TOKEN_RESOURCE_BASE_PATH).request()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .post(Entity.json(""))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            var validated = response.readEntity(ValidatedToken.class);
            assertThat(validated.expiresAt()).isEqualTo(persistedExpiry);
            assertThat(validated.resource()).isEqualTo(RESOURCE_URI);
            assertThat(validated.workspaceName()).isEqualTo(minted.tokens().workspaceName());
        }
    }

    private Instant fetchTokenExpiry(String tokenHash) {
        return transactionTemplate.inTransaction(
                handle -> handle.attach(McpOAuthTokenDAO.class).fetch(tokenHash).map(McpOAuthToken::expiresAt)
                        .orElse(null));
    }
}
