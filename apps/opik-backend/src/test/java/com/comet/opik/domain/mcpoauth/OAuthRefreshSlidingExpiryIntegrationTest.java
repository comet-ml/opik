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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Refresh-token lifetime is sliding: a connector that keeps rotating stays connected, an idle one expires after
 * {@code refreshTokenTtl}, and no family outlives {@code refreshTokenAbsoluteTtl} from its authorization. Before
 * this, every rotation inherited the first token's expiry, so an actively used Claude.ai connector was thrown out
 * exactly seven days after it was connected.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@DisplayName("OAuth Refresh Sliding Expiry Integration Test")
class OAuthRefreshSlidingExpiryIntegrationTest {

    private static final String REDIRECT_URI = "http://localhost:1234/callback";
    private static final String RESOURCE_URI = "http://localhost:8080/api/v1/mcp";
    private static final Duration IDLE_TTL = Duration.ofMinutes(2);
    // The cap only bites once more than (ABSOLUTE - IDLE) has elapsed since authorization: 6s here, so the first
    // rotation (after ~2s) is still sliding and the second (after ~10s) is capped, each with a ~4s margin.
    private static final Duration ABSOLUTE_TTL = IDLE_TTL.plusSeconds(6);
    private static final Duration FIRST_PAUSE = Duration.ofSeconds(2);
    private static final Duration SECOND_PAUSE = Duration.ofSeconds(8);
    private static final Duration TOLERANCE = Duration.ofSeconds(2);

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
                                new CustomConfig("mcpOAuth.refreshTokenTtl", IDLE_TTL.toString()),
                                new CustomConfig("mcpOAuth.refreshTokenAbsoluteTtl", ABSOLUTE_TTL.toString())))
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
    @DisplayName("each rotation renews the refresh lifetime, up to the family's absolute lifetime")
    void rotationRenewsRefreshLifetime_untilAbsoluteCap() throws InterruptedException {
        var minted = oauthClient.mintArtifacts();
        String clientId = minted.clientId();

        McpOAuthToken original = fetchRefreshRow(minted.tokens().refreshToken());
        Instant familyStart = original.issuedAt();
        Instant absoluteExpiry = original.absoluteExpiresAt();
        assertThat(absoluteExpiry).as("the authorization fixes the family's absolute expiry")
                .isCloseTo(familyStart.plus(ABSOLUTE_TTL), within(TOLERANCE));
        assertThat(original.expiresAt()).isCloseTo(familyStart.plus(IDLE_TTL), within(TOLERANCE));

        Thread.sleep(FIRST_PAUSE.toMillis());
        TokenResponse first = oauthClient.refreshOk(clientId, minted.tokens().refreshToken());
        McpOAuthToken afterFirst = fetchRefreshRow(first.refreshToken());
        assertThat(afterFirst.absoluteExpiresAt()).as("rotation carries the family's absolute expiry forward")
                .isEqualTo(absoluteExpiry);
        assertThat(afterFirst.expiresAt())
                .as("the rotated refresh token lives a full idle TTL from the rotation, not from the authorization")
                .isAfter(original.expiresAt())
                .isCloseTo(afterFirst.issuedAt().plus(IDLE_TTL), within(TOLERANCE));

        Thread.sleep(SECOND_PAUSE.toMillis());
        TokenResponse second = oauthClient.refreshOk(clientId, first.refreshToken());
        McpOAuthToken afterSecond = fetchRefreshRow(second.refreshToken());
        assertThat(afterSecond.absoluteExpiresAt()).isEqualTo(absoluteExpiry);
        assertThat(afterSecond.expiresAt())
                .as("once idle TTL from now would pass the family's absolute lifetime, the cap wins")
                .isEqualTo(absoluteExpiry)
                .isBefore(afterSecond.issuedAt().plus(IDLE_TTL).minusSeconds(1));
    }

    @Test
    @DisplayName("a refresh token minted before the absolute expiry existed gets its cap at the next rotation")
    void legacyTokenWithoutAbsoluteExpiry_capStartsAtNextRotation() {
        var minted = oauthClient.mintArtifacts();
        String clientId = minted.clientId();
        clearAbsoluteExpiry(minted.tokens().refreshToken());
        assertThat(fetchRefreshRow(minted.tokens().refreshToken()).absoluteExpiresAt()).isNull();

        TokenResponse first = oauthClient.refreshOk(clientId, minted.tokens().refreshToken());
        McpOAuthToken afterFirst = fetchRefreshRow(first.refreshToken());
        assertThat(afterFirst.absoluteExpiresAt())
                .as("the first rotation of a legacy token starts the family's absolute lifetime from that rotation")
                .isNotNull()
                .isCloseTo(afterFirst.issuedAt().plus(ABSOLUTE_TTL), within(TOLERANCE));

        TokenResponse second = oauthClient.refreshOk(clientId, first.refreshToken());
        assertThat(fetchRefreshRow(second.refreshToken()).absoluteExpiresAt())
                .as("later rotations carry that cap forward unchanged")
                .isEqualTo(afterFirst.absoluteExpiresAt());
    }

    /** Makes a token row look like one minted before {@code absolute_expires_at} existed. */
    private void clearAbsoluteExpiry(String refreshToken) {
        transactionTemplate.inTransaction(handle -> handle
                .createUpdate("UPDATE mcp_oauth_tokens SET absolute_expires_at = NULL WHERE token_hash = :hash")
                .bind("hash", McpOAuthTokenUtils.hash(refreshToken))
                .execute());
    }

    private McpOAuthToken fetchRefreshRow(String refreshToken) {
        return transactionTemplate.inTransaction(handle -> handle.attach(McpOAuthTokenDAO.class)
                .fetch(McpOAuthTokenUtils.hash(refreshToken))
                .orElseThrow());
    }
}
