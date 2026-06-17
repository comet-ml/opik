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
import org.awaitility.Awaitility;
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
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the wired scrub job: an authorization code and token pair are minted through the public OAuth
 * endpoints, expire, and the job removes them when fired. The business-logic edge cases live in
 * {@link McpOAuthScrubServiceTest}.
 *
 * <p>Deletion is asserted by reading rows back through the DAOs rather than the API. This is a deliberate, narrow
 * exception: the validate endpoint returns 401 for both an expired-but-present token and one the scrub deleted, so the
 * API cannot prove physical removal — a 401 would pass even if the job did nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("McpOAuthScrubJob")
@ExtendWith(DropwizardAppExtensionProvider.class)
class McpOAuthScrubJobTest {

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

        // No react-service runtimeInfo on purpose: the app runs with local auth, so the consent step resolves to the
        // default workspace without a session. TTLs are short so endpoint-minted artifacts expire within the test.
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("mcpOAuth.enabled", "true"),
                                new CustomConfig("mcpOAuth.baseUrl", "http://localhost:8080"),
                                new CustomConfig("mcpOAuth.mcpResourceUri", RESOURCE_URI),
                                new CustomConfig("mcpOAuth.accessTokenTtl", "PT1S"),
                                new CustomConfig("mcpOAuth.refreshTokenTtl", "PT1S"),
                                new CustomConfig("mcpOAuth.codeTtl", "PT1S"),
                                new CustomConfig("mcpOAuth.refreshRotationGrace", "PT0S")))
                        .build());
    }

    private TransactionTemplate transactionTemplate;
    private McpOAuthScrubJob job;
    private OAuthResourceClient oauthClient;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, TransactionTemplate transactionTemplate, McpOAuthScrubJob job) {
        this.transactionTemplate = transactionTemplate;
        this.job = job;
        this.oauthClient = new OAuthResourceClient(
                clientSupport, TestUtils.getBaseUrl(clientSupport), REDIRECT_URI, RESOURCE_URI);
    }

    @Test
    @DisplayName("scrub removes codes and tokens minted through the OAuth endpoints once they expire")
    void scrubsExpiredArtifactsFromOAuthFlow() {
        var minted = oauthClient.mintArtifacts();

        String codeHash = McpOAuthTokenUtils.hash(minted.code());
        String accessHash = McpOAuthTokenUtils.hash(minted.tokens().accessToken());
        String refreshHash = McpOAuthTokenUtils.hash(minted.tokens().refreshToken());

        // The exchanged code is single-use but its row lingers until it expires and the scrub deletes it.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> isExpired(fetchCodeExpiry(codeHash)) && isExpired(fetchTokenExpiry(accessHash)));

        job.doJob(null);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            assertThat(codeExists(codeHash)).isFalse();
            assertThat(tokenExists(accessHash)).isFalse();
            assertThat(tokenExists(refreshHash)).isFalse();
        });
    }

    /** Polls the persisted expiry instant, which the API does not expose, to wait until the artifact is scrub-eligible. */
    private Instant fetchCodeExpiry(String codeHash) {
        return transactionTemplate.inTransaction(
                handle -> handle.attach(McpOAuthCodeDAO.class).fetch(codeHash).map(McpOAuthCode::expiresAt)
                        .orElse(null));
    }

    /** Polls the persisted expiry instant, which the API does not expose, to wait until the artifact is scrub-eligible. */
    private Instant fetchTokenExpiry(String tokenHash) {
        return transactionTemplate.inTransaction(
                handle -> handle.attach(McpOAuthTokenDAO.class).fetch(tokenHash).map(McpOAuthToken::expiresAt)
                        .orElse(null));
    }

    /** Reads the row back to assert physical deletion; see the class javadoc for why the API cannot verify this. */
    private boolean codeExists(String codeHash) {
        return transactionTemplate
                .inTransaction(handle -> handle.attach(McpOAuthCodeDAO.class).fetch(codeHash).isPresent());
    }

    /** Reads the row back to assert physical deletion; see the class javadoc for why the API cannot verify this. */
    private boolean tokenExists(String tokenHash) {
        return transactionTemplate
                .inTransaction(handle -> handle.attach(McpOAuthTokenDAO.class).fetch(tokenHash).isPresent());
    }

    private static boolean isExpired(Instant expiresAt) {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }
}
