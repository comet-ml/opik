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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_GRANT;
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
    // One rotation plus this many in-grace retries may be served off a single refresh token.
    private static final int MAX_RETRIES = PARALLEL_REFRESHES - 1;
    // More parallel calls than the cap can serve: 1 rotation + MAX_RETRIES retries + 2 over the cap.
    private static final int OVERSUBSCRIBED_REFRESHES = MAX_RETRIES + 3;
    private static final Duration BURST_TIMEOUT = Duration.ofSeconds(30);

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
                                new CustomConfig("mcpOAuth.refreshRotationGrace", "PT2M"),
                                new CustomConfig("mcpOAuth.refreshRotationMaxRetries",
                                        String.valueOf(MAX_RETRIES))))
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

        List<OAuthResourceClient.RefreshOutcome> outcomes = refreshBurst(PARALLEL_REFRESHES, clientId, refreshToken);

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
        for (OAuthResourceClient.RefreshOutcome outcome : outcomes) {
            OAuthResourceClient.RefreshOutcome next = oauthClient.refresh(clientId, outcome.tokens().refreshToken());
            assertThat(next.status())
                    .as("a refresh token handed out during the burst rotates normally afterwards")
                    .isEqualTo(Response.Status.OK.getStatusCode());
        }
    }

    @Test
    @DisplayName("a burst larger than the retry cap refuses the surplus but keeps the family alive")
    void burstBeyondCap_refusesSurplusWithoutKillingFamily() {
        var minted = oauthClient.mintArtifacts();
        String clientId = minted.clientId();

        List<OAuthResourceClient.RefreshOutcome> outcomes = refreshBurst(OVERSUBSCRIBED_REFRESHES, clientId,
                minted.tokens().refreshToken());

        List<OAuthResourceClient.RefreshOutcome> served = outcomes.stream()
                .filter(OAuthResourceClient.RefreshOutcome::isOk)
                .toList();
        assertThat(served)
                .as("the rotation plus every in-grace retry the cap allows is served")
                .hasSize(MAX_RETRIES + 1);
        assertThat(outcomes)
                .filteredOn(outcome -> !outcome.isOk())
                .as("the surplus is refused with invalid_grant")
                .isNotEmpty()
                .allSatisfy(outcome -> {
                    assertThat(outcome.status()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
                    assertThat(outcome.error().error()).isEqualTo(ERROR_INVALID_GRANT);
                });

        // The point of the test: hitting the cap must not take down credentials the host already holds.
        for (OAuthResourceClient.RefreshOutcome outcome : served) {
            assertThat(oauthClient.refresh(clientId, outcome.tokens().refreshToken()).status())
                    .as("a pair served before the cap was hit still rotates")
                    .isEqualTo(Response.Status.OK.getStatusCode());
        }
    }

    @Test
    @DisplayName("sequential retries past the cap are refused one by one while the family stays alive")
    void retriesBeyondCap_refusedWithoutKillingFamily() {
        var minted = oauthClient.mintArtifacts();
        String clientId = minted.clientId();
        String original = minted.tokens().refreshToken();

        OAuthResourceClient.RefreshOutcome rotation = oauthClient.refresh(clientId, original);
        assertThat(rotation.status()).isEqualTo(Response.Status.OK.getStatusCode());

        List<String> issued = new ArrayList<>(List.of(rotation.tokens().refreshToken()));
        for (int retry = 1; retry <= MAX_RETRIES; retry++) {
            OAuthResourceClient.RefreshOutcome allowed = oauthClient.refresh(clientId, original);
            assertThat(allowed.status()).as("retry %d of %d is still served", retry, MAX_RETRIES)
                    .isEqualTo(Response.Status.OK.getStatusCode());
            assertThat(allowed.tokens().accessToken()).isNotBlank();
            assertThat(allowed.tokens().refreshToken()).isNotBlank();
            issued.add(allowed.tokens().refreshToken());
        }
        assertThat(issued).as("every served retry carries its own refresh token").doesNotHaveDuplicates();

        for (int surplus = 1; surplus <= 2; surplus++) {
            OAuthResourceClient.RefreshOutcome overCap = oauthClient.refresh(clientId, original);
            assertThat(overCap.status()).as("request %d past the cap is refused", surplus)
                    .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
            assertThat(overCap.error().error()).isEqualTo(ERROR_INVALID_GRANT);
        }

        for (String refreshToken : issued) {
            assertThat(oauthClient.refresh(clientId, refreshToken).status())
                    .as("every pair handed out before the cap still rotates")
                    .isEqualTo(Response.Status.OK.getStatusCode());
        }
    }

    @Test
    @DisplayName("an in-grace retry is refused once the client has revoked the family")
    void retryAfterClientRevocation_isRejected() {
        var minted = oauthClient.mintArtifacts();
        String clientId = minted.clientId();
        String original = minted.tokens().refreshToken();

        OAuthResourceClient.RefreshOutcome rotation = oauthClient.refresh(clientId, original);
        assertThat(rotation.status()).isEqualTo(Response.Status.OK.getStatusCode());

        oauthClient.revoke(clientId, rotation.tokens().refreshToken());

        OAuthResourceClient.RefreshOutcome retry = oauthClient.refresh(clientId, original);
        assertThat(retry.status()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        assertThat(retry.error().error()).isEqualTo(ERROR_INVALID_GRANT);
    }

    /** One dedicated thread per request, all released by the same barrier, so the burst really is concurrent. */
    private List<OAuthResourceClient.RefreshOutcome> refreshBurst(int size, String clientId, String refreshToken) {
        var barrier = new CyclicBarrier(size);
        ExecutorService workers = Executors.newFixedThreadPool(size);
        try {
            return IntStream.range(0, size)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        try {
                            barrier.await(BURST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException("burst did not line up", e);
                        }
                        return oauthClient.refresh(clientId, refreshToken);
                    }, workers))
                    .toList()
                    .stream()
                    .map(future -> future.orTimeout(BURST_TIMEOUT.toSeconds(), TimeUnit.SECONDS).join())
                    .toList();
        } finally {
            workers.shutdownNow();
        }
    }
}
