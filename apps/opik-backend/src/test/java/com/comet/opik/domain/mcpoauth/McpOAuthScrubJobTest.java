package com.comet.opik.domain.mcpoauth;

import com.comet.opik.api.resources.oauth.AuthorizeContext;
import com.comet.opik.api.resources.oauth.ClientRegistrationResponse;
import com.comet.opik.api.resources.oauth.ConsentRequest;
import com.comet.opik.api.resources.oauth.ConsentResponse;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
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
import uk.co.jemos.podam.api.PodamFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.AUTHORIZE_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.CODE_CHALLENGE_METHOD_S256;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.CSRF_COOKIE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_AUTHORIZATION_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE_VERIFIER;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_GRANT_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REDIRECT_URI;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.REGISTER_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.TOKEN_PATH;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the scrub job end-to-end: an authorization code and token pair are minted through the public OAuth
 * endpoints, expire, and are then removed by the wired job. The predicate edge cases that a single global TTL cannot
 * express (keeping unexpired rows, the revocation grace window) are pinned at the DAO level.
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

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private TransactionTemplate transactionTemplate;
    private McpOAuthScrubJob job;
    private OAuthClient oauthClient;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, TransactionTemplate transactionTemplate, McpOAuthScrubJob job) {
        this.transactionTemplate = transactionTemplate;
        this.job = job;
        this.oauthClient = new OAuthClient(clientSupport, TestUtils.getBaseUrl(clientSupport));
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

    @Test
    @DisplayName("deleteExpired keeps codes that have not reached their expiry")
    void daoKeepsUnexpiredCodes() {
        Instant now = Instant.now();
        McpOAuthCode expired = insertCode(now.minus(Duration.ofHours(1)));
        McpOAuthCode valid = insertCode(now.plus(Duration.ofHours(1)));

        transactionTemplate.inTransaction(WRITE, handle -> handle.attach(McpOAuthCodeDAO.class).deleteExpired(now));

        assertThat(codeExists(expired.codeHash())).isFalse();
        assertThat(codeExists(valid.codeHash())).isTrue();
    }

    @Test
    @DisplayName("deleteExpiredAndRevoked drops tokens revoked before the grace threshold and keeps newer ones")
    void daoDeletesRevokedTokensOnlyPastGrace() {
        Instant now = Instant.now();
        Duration grace = Duration.ofMinutes(5);
        Instant threshold = now.minus(grace);
        Instant farFuture = now.plus(Duration.ofHours(1));

        McpOAuthToken withinGrace = insertToken(farFuture, now.minus(grace.dividedBy(2)));
        McpOAuthToken pastGrace = insertToken(farFuture, now.minus(grace.plusMinutes(1)));
        McpOAuthToken valid = insertToken(farFuture, null);

        transactionTemplate.inTransaction(WRITE,
                handle -> handle.attach(McpOAuthTokenDAO.class).deleteExpiredAndRevoked(threshold));

        assertThat(tokenExists(withinGrace.tokenHash())).isTrue();
        assertThat(tokenExists(pastGrace.tokenHash())).isFalse();
        assertThat(tokenExists(valid.tokenHash())).isTrue();
    }

    private McpOAuthCode insertCode(Instant expiresAt) {
        McpOAuthCode code = factory.manufacturePojo(McpOAuthCode.class).toBuilder()
                .id(UUID.randomUUID().toString())
                .codeHash(randomHash())
                .clientId(UUID.randomUUID().toString())
                .codeChallengeMethod("S256")
                .expiresAt(expiresAt)
                .build();

        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(McpOAuthCodeDAO.class).save(code);
            return null;
        });

        return code;
    }

    private McpOAuthToken insertToken(Instant expiresAt, Instant revokedAt) {
        McpOAuthToken token = factory.manufacturePojo(McpOAuthToken.class).toBuilder()
                .id(UUID.randomUUID().toString())
                .tokenHash(randomHash())
                .type(McpOAuthToken.TYPE_ACCESS)
                .clientId(UUID.randomUUID().toString())
                .familyId(UUID.randomUUID().toString())
                .rotatedFromId(null)
                .expiresAt(expiresAt)
                .build();

        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(McpOAuthTokenDAO.class).save(token);
            if (revokedAt != null) {
                handle.createUpdate("UPDATE mcp_oauth_tokens SET revoked_at = :revokedAt WHERE id = :id")
                        .bind("revokedAt", revokedAt)
                        .bind("id", token.id())
                        .execute();
            }
            return null;
        });

        return token;
    }

    private Instant fetchCodeExpiry(String codeHash) {
        return transactionTemplate.inTransaction(
                handle -> handle.attach(McpOAuthCodeDAO.class).fetch(codeHash).map(McpOAuthCode::expiresAt)
                        .orElse(null));
    }

    private Instant fetchTokenExpiry(String tokenHash) {
        return transactionTemplate.inTransaction(
                handle -> handle.attach(McpOAuthTokenDAO.class).fetch(tokenHash).map(McpOAuthToken::expiresAt)
                        .orElse(null));
    }

    private boolean codeExists(String codeHash) {
        return transactionTemplate
                .inTransaction(handle -> handle.attach(McpOAuthCodeDAO.class).fetch(codeHash).isPresent());
    }

    private boolean tokenExists(String tokenHash) {
        return transactionTemplate
                .inTransaction(handle -> handle.attach(McpOAuthTokenDAO.class).fetch(tokenHash).isPresent());
    }

    private static boolean isExpired(Instant expiresAt) {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    private static String randomHash() {
        return RandomStringUtils.secure().nextAlphanumeric(64);
    }

    /**
     * Drives the MCP OAuth authorization-code + PKCE flow through the public endpoints so the test mints real codes and
     * tokens without reaching into the persistence layer. Targets an app running with local auth, where consent
     * resolves to the default workspace.
     */
    private record OAuthClient(ClientSupport client, String baseURI) {

        private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
        private static final String CONTEXT_PATH = AUTHORIZE_PATH + "/context";

        /** Registers a client, walks consent + PKCE, and exchanges the code for the raw code and the token pair. */
        private Minted mintArtifacts() {
            String clientId = registerClient();
            String codeVerifier = RandomStringUtils.secure().nextAlphanumeric(64);
            String code = authorize(clientId, codeVerifier);
            return new Minted(code, exchangeCode(clientId, code, codeVerifier));
        }

        private String registerClient() {
            var request = ClientRegistrationRequest.builder()
                    .clientName(RandomStringUtils.secure().nextAlphanumeric(10))
                    .redirectUris(Set.of(REDIRECT_URI))
                    .build();

            try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
                return response.readEntity(ClientRegistrationResponse.class).clientId();
            }
        }

        private String authorize(String clientId, String codeVerifier) {
            String csrf = consentContext(clientId).csrfToken();

            var consent = ConsentRequest.builder()
                    .clientId(clientId)
                    .redirectUri(REDIRECT_URI)
                    .codeChallenge(codeChallenge(codeVerifier))
                    .codeChallengeMethod(CODE_CHALLENGE_METHOD_S256)
                    .resource(RESOURCE_URI)
                    .workspaceName(ProjectService.DEFAULT_WORKSPACE_NAME)
                    .csrf(csrf)
                    .build();

            try (var response = client.target(baseURI + AUTHORIZE_PATH).request()
                    .cookie(CSRF_COOKIE, csrf)
                    .post(Entity.json(consent))) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
                return extractCode(response.readEntity(ConsentResponse.class).redirectTo());
            }
        }

        private TokenResponse exchangeCode(String clientId, String code, String codeVerifier) {
            var form = new Form()
                    .param(PARAM_GRANT_TYPE, GRANT_AUTHORIZATION_CODE)
                    .param(PARAM_CODE, code)
                    .param(PARAM_REDIRECT_URI, REDIRECT_URI)
                    .param(PARAM_CLIENT_ID, clientId)
                    .param(PARAM_CODE_VERIFIER, codeVerifier);

            try (var response = client.target(baseURI + TOKEN_PATH).request().post(Entity.form(form))) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
                return response.readEntity(TokenResponse.class);
            }
        }

        private AuthorizeContext consentContext(String clientId) {
            try (var response = client.target(baseURI + CONTEXT_PATH)
                    .queryParam(PARAM_CLIENT_ID, clientId)
                    .queryParam(PARAM_REDIRECT_URI, REDIRECT_URI)
                    .request()
                    .get()) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
                return response.readEntity(AuthorizeContext.class);
            }
        }

        private static String codeChallenge(String codeVerifier) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
                return URL_ENCODER.encodeToString(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 not available", e);
            }
        }

        private static String extractCode(String redirectTo) {
            for (String pair : URI.create(redirectTo).getQuery().split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && PARAM_CODE.equals(pair.substring(0, eq))) {
                    return pair.substring(eq + 1);
                }
            }
            throw new IllegalStateException("No authorization code in redirect: " + redirectTo);
        }

        private record Minted(String code, TokenResponse tokens) {
        }
    }
}
