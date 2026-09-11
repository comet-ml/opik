package com.comet.opik.domain.mcpoauth;

import com.comet.opik.api.resources.oauth.OAuthError;
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
import jakarta.ws.rs.core.Form;
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

import java.time.Duration;
import java.util.List;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_GRANT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_REFRESH_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_GRANT_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REFRESH_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.TOKEN_PATH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security half of rotation (OAuth 2.1 §4.3.1): once the rotation grace has passed, a rotated refresh token
 * presented again is a replay, and the whole family, the freshly rotated token included, is revoked. Runs its own
 * app with a one-second grace so the window can actually be crossed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@DisplayName("OAuth Refresh Reuse Detection Integration Test")
class OAuthRefreshReuseDetectionIntegrationTest {

    private static final String REDIRECT_URI = "http://localhost:1234/callback";
    private static final String RESOURCE_URI = "http://localhost:8080/api/v1/mcp";
    private static final Duration GRACE = Duration.ofSeconds(1);

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

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("mcpOAuth.enabled", "true"),
                                new CustomConfig("mcpOAuth.baseUrl", "http://localhost:8080"),
                                new CustomConfig("mcpOAuth.mcpResourceUri", RESOURCE_URI),
                                new CustomConfig("mcpOAuth.refreshRotationGrace", GRACE.toString())))
                        .build());
    }

    private String baseURI;
    private ClientSupport client;
    private OAuthResourceClient oauthClient;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport) {
        this.client = clientSupport;
        this.baseURI = TestUtils.getBaseUrl(clientSupport);
        this.oauthClient = new OAuthResourceClient(clientSupport, baseURI, REDIRECT_URI, RESOURCE_URI);
    }

    @Test
    @DisplayName("a rotated refresh token presented after the grace window revokes the whole family")
    void rotatedTokenPresentedAfterGrace_revokesFamily() throws InterruptedException {
        var minted = oauthClient.mintArtifacts();
        String clientId = minted.clientId();
        String originalRefreshToken = minted.tokens().refreshToken();

        RefreshOutcome rotated = refresh(clientId, originalRefreshToken);
        assertThat(rotated.status()).isEqualTo(Response.Status.OK.getStatusCode());
        String currentRefreshToken = rotated.tokens().refreshToken();

        // Past the grace window the original token is a replay...
        Thread.sleep(GRACE.plusMillis(500).toMillis());
        RefreshOutcome replay = refresh(clientId, originalRefreshToken);
        assertThat(replay.status()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        assertThat(replay.error().error()).isEqualTo(ERROR_INVALID_GRANT);

        // ...and the token the legitimate rotation produced goes down with it.
        RefreshOutcome afterReuse = refresh(clientId, currentRefreshToken);
        assertThat(afterReuse.status()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        assertThat(afterReuse.error().error()).isEqualTo(ERROR_INVALID_GRANT);
    }

    private RefreshOutcome refresh(String clientId, String refreshToken) {
        var form = new Form()
                .param(PARAM_GRANT_TYPE, GRANT_REFRESH_TOKEN)
                .param(PARAM_CLIENT_ID, clientId)
                .param(PARAM_REFRESH_TOKEN, refreshToken);

        try (Response response = client.target(baseURI + TOKEN_PATH).request().post(Entity.form(form))) {
            int status = response.getStatus();
            if (status == Response.Status.OK.getStatusCode()) {
                return new RefreshOutcome(status, response.readEntity(TokenResponse.class), null);
            }
            return new RefreshOutcome(status, null, response.readEntity(OAuthError.class));
        }
    }

    private record RefreshOutcome(int status, TokenResponse tokens, OAuthError error) {
    }
}
