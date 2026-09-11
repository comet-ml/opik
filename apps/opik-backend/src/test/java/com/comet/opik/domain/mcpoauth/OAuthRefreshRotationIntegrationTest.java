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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_REFRESH_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_GRANT_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REFRESH_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.TOKEN_PATH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refresh-token rotation as an MCP host actually exercises it. Hosts (Claude.ai, Codex, Claude Code) fire several
 * tool calls in parallel; when the access token expires every one of them gets a 401 and every one of them runs the
 * {@code refresh_token} grant with the same stored refresh token. Only one of those requests can win the rotation.
 * The losers must still walk away with a usable token pair: a host that receives {@code invalid_grant} on refresh
 * discards its credentials and forces the user to re-authorize.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@DisplayName("OAuth Refresh Rotation Integration Test")
class OAuthRefreshRotationIntegrationTest {

    private static final String REDIRECT_URI = "http://localhost:1234/callback";
    private static final String RESOURCE_URI = "http://localhost:8080/api/v1/mcp";
    private static final int PARALLEL_REFRESHES = 5;

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

        // Local auth, so consent resolves to the default workspace without a session. The grace window is long
        // enough that every request of a parallel burst lands inside it.
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("mcpOAuth.enabled", "true"),
                                new CustomConfig("mcpOAuth.baseUrl", "http://localhost:8080"),
                                new CustomConfig("mcpOAuth.mcpResourceUri", RESOURCE_URI),
                                new CustomConfig("mcpOAuth.refreshRotationGrace", "PT2M")))
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
    @DisplayName("parallel refreshes with the same refresh token all receive a usable token pair")
    void parallelRefreshesWithSameToken_allReceiveUsableTokens() {
        var minted = oauthClient.mintArtifacts();
        String clientId = minted.clientId();
        String refreshToken = minted.tokens().refreshToken();

        List<RefreshOutcome> outcomes = IntStream.range(0, PARALLEL_REFRESHES)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> refresh(clientId, refreshToken)))
                .toList()
                .stream()
                .map(CompletableFuture::join)
                .toList();

        assertThat(outcomes)
                .as("every parallel refresh with the same refresh token is answered with a token pair")
                .allSatisfy(outcome -> {
                    assertThat(outcome.status()).isEqualTo(Response.Status.OK.getStatusCode());
                    assertThat(outcome.tokens().accessToken()).isNotBlank();
                    assertThat(outcome.tokens().refreshToken()).isNotBlank();
                });
        assertThat(outcomes).extracting(outcome -> outcome.tokens().refreshToken())
                .as("each answer of the burst carries its own refresh token")
                .doesNotHaveDuplicates();

        // Whichever copy of the credentials the host keeps must keep working: every refresh token handed out by
        // the burst rotates on its own.
        for (RefreshOutcome outcome : outcomes) {
            RefreshOutcome next = refresh(clientId, outcome.tokens().refreshToken());
            assertThat(next.status())
                    .as("a refresh token handed out during the burst rotates normally afterwards")
                    .isEqualTo(Response.Status.OK.getStatusCode());
        }
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
