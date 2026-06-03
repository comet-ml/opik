package com.comet.opik.api.resources.oauth;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientProperties;
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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full-stack OAuth 2.1 flow against a real app + MySQL: registration, PKCE dance, token lifecycle.
 * Exercises persistence semantics (code burn, rotation, reuse detection) that mock-based resource
 * tests cannot see. Runs in OSS mode (auth disabled), where consent resolves to the default
 * workspace without a session.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@DisplayName("MCP OAuth Flow E2E Test")
class McpOAuthFlowE2ETest {

    private static final String BASE_URL = "http://localhost";
    private static final String RESOURCE_URI = BASE_URL + "/api/v1/mcp";
    private static final String REDIRECT_URI = "http://127.0.0.1/callback";
    private static final String CSRF_COOKIE = "mcp_oauth_csrf";
    // Keep in sync with mcpOAuth.refreshRotationGrace below: long enough that an immediate replay is
    // reliably benign, short enough that the reuse-detection test doesn't stall the suite.
    private static final long ROTATION_GRACE_MS = 2_000;

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

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("mcpOAuth.enabled", "true"),
                                new CustomConfig("mcpOAuth.baseUrl", BASE_URL),
                                new CustomConfig("mcpOAuth.refreshRotationGrace", "PT2S"),
                                // resolve the @On placeholder; midnight so the job never fires mid-test
                                new CustomConfig("jobs.mcpOAuthScrubCron", "0 0 0 * * ?")))
                        .build());
    }

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void beforeAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        ClientSupportUtils.config(client);
    }

    // --- flow helpers ---------------------------------------------------------------------------

    private String registerClient() {
        try (var response = client.target(baseURI + "/oauth/register")
                .request()
                .post(Entity.json(Map.of(
                        "client_name", "E2E Test Client",
                        "redirect_uris", Set.of(REDIRECT_URI))))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.CREATED.getStatusCode());
            return (String) response.readEntity(Map.class).get("client_id");
        }
    }

    private record PkcePair(String verifier, String challenge) {
        static PkcePair generate() {
            String verifier = "verifier-" + UUID.randomUUID();
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII));
                return new PkcePair(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private String mintCode(String clientId, PkcePair pkce) {
        NewCookie csrfCookie;
        String csrfToken;
        try (var response = client.target(baseURI + "/oauth/authorize/context")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", REDIRECT_URI)
                .request()
                .get()) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            csrfCookie = response.getCookies().get(CSRF_COOKIE);
            assertThat(csrfCookie).isNotNull();
            csrfToken = (String) response.readEntity(Map.class).get("csrf_token");
        }

        Map<String, String> consent = new HashMap<>();
        consent.put("client_id", clientId);
        consent.put("redirect_uri", REDIRECT_URI);
        consent.put("code_challenge", pkce.challenge());
        consent.put("code_challenge_method", "S256");
        consent.put("resource", RESOURCE_URI);
        consent.put("state", "st-123");
        consent.put("workspace_name", DEFAULT_WORKSPACE_NAME);
        consent.put("csrf", csrfToken);

        try (var response = client.target(baseURI + "/oauth/authorize")
                .request()
                .cookie(new Cookie.Builder(CSRF_COOKIE).value(csrfCookie.getValue()).build())
                .post(Entity.json(consent))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            String redirectTo = (String) response.readEntity(Map.class).get("redirect_to");
            assertThat(redirectTo).startsWith(REDIRECT_URI);
            return queryParam(redirectTo, "code");
        }
    }

    private static String queryParam(String url, String name) {
        return Arrays.stream(URI.create(url).getQuery().split("&"))
                .filter(pair -> pair.startsWith(name + "="))
                .map(pair -> pair.substring(name.length() + 1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing query param '%s' in '%s'".formatted(name, url)));
    }

    private Response exchangeCode(String clientId, String code, String verifier) {
        return client.target(baseURI + "/oauth/token")
                .request()
                .post(Entity.form(new Form()
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", clientId)
                        .param("code_verifier", verifier)));
    }

    private Response refreshToken(String clientId, String refreshToken) {
        return client.target(baseURI + "/oauth/token")
                .request()
                .post(Entity.form(new Form()
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken)
                        .param("client_id", clientId)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mintTokens(String clientId) {
        var pkce = PkcePair.generate();
        String code = mintCode(clientId, pkce);
        try (var response = exchangeCode(clientId, code, pkce.verifier())) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            return response.readEntity(Map.class);
        }
    }

    private Response callDataApi(String accessToken) {
        return client.target(baseURI + "/v1/private/projects")
                .request()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .get();
    }

    private static void assertOAuthError(Response response, String expectedError) {
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        assertThat(response.readEntity(Map.class).get("error")).isEqualTo(expectedError);
    }

    // --- tests ----------------------------------------------------------------------------------

    @Test
    @DisplayName("RFC 8414 metadata advertises issuer and endpoints")
    void metadata() {
        try (var response = client.target(baseURI + "/.well-known/oauth-authorization-server")
                .request()
                .get()) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            Map<String, Object> body = response.readEntity(Map.class);
            assertThat(body.get("issuer")).isEqualTo(BASE_URL);
            assertThat(body.get("token_endpoint")).isEqualTo(BASE_URL + "/oauth/token");
            assertThat(body.get("registration_endpoint")).isEqualTo(BASE_URL + "/oauth/register");
        }
    }

    @Test
    @DisplayName("GET /oauth/authorize redirects a valid request to the consent UI")
    void authorizeRedirectsToConsent() {
        String clientId = registerClient();
        var pkce = PkcePair.generate();

        try (var response = client.target(baseURI + "/oauth/authorize")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("response_type", "code")
                .queryParam("code_challenge", pkce.challenge())
                .queryParam("code_challenge_method", "S256")
                .queryParam("resource", RESOURCE_URI)
                .queryParam("state", "st-42")
                .request()
                .property(ClientProperties.FOLLOW_REDIRECTS, false)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.FOUND.getStatusCode());
            assertThat(response.getLocation().toString()).startsWith(BASE_URL + "/oauth/consent?");
        }
    }

    @Test
    @DisplayName("full PKCE flow: register -> consent -> code -> tokens -> bearer-authenticated data API call")
    void fullAuthorizationCodeFlow() {
        String clientId = registerClient();
        Map<String, Object> tokens = mintTokens(clientId);

        String accessToken = (String) tokens.get("access_token");
        assertThat(accessToken).startsWith("opik_at_");
        assertThat((String) tokens.get("refresh_token")).startsWith("opik_rt_");
        assertThat(tokens.get("token_type")).isEqualTo("Bearer");
        assertThat(tokens.get("workspace_name")).isEqualTo(DEFAULT_WORKSPACE_NAME);

        try (var response = callDataApi(accessToken)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }

        // workspace header must match the token's workspace when present
        try (var response = client.target(baseURI + "/v1/private/projects")
                .request()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(WORKSPACE_HEADER, "some-other-workspace")
                .get()) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.FORBIDDEN.getStatusCode());
        }
    }

    @Test
    @DisplayName("authorization code is single-use: second exchange fails")
    void codeDoubleSpendRejected() {
        String clientId = registerClient();
        var pkce = PkcePair.generate();
        String code = mintCode(clientId, pkce);

        try (var response = exchangeCode(clientId, code, pkce.verifier())) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }
        try (var response = exchangeCode(clientId, code, pkce.verifier())) {
            assertOAuthError(response, "invalid_grant");
        }
    }

    @Test
    @DisplayName("failed PKCE attempt burns the code: retry with the correct verifier also fails")
    void failedPkceBurnsCode() {
        String clientId = registerClient();
        var pkce = PkcePair.generate();
        String code = mintCode(clientId, pkce);

        try (var response = exchangeCode(clientId, code, "wrong-verifier")) {
            assertOAuthError(response, "invalid_grant");
        }
        // The burn must have been committed despite the failure — the code is not replayable.
        try (var response = exchangeCode(clientId, code, pkce.verifier())) {
            assertOAuthError(response, "invalid_grant");
        }
    }

    @Test
    @DisplayName("refresh rotation: new pair minted; immediate replay of the rotated token is benign (grace)")
    void refreshRotationWithGrace() {
        String clientId = registerClient();
        Map<String, Object> tokens = mintTokens(clientId);
        String oldRefresh = (String) tokens.get("refresh_token");

        String newRefresh;
        try (var response = refreshToken(clientId, oldRefresh)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            Map<String, Object> rotated = response.readEntity(Map.class);
            newRefresh = (String) rotated.get("refresh_token");
            assertThat(newRefresh).isNotEqualTo(oldRefresh);
        }

        // Within the grace window: invalid_grant, but the family survives
        try (var response = refreshToken(clientId, oldRefresh)) {
            assertOAuthError(response, "invalid_grant");
        }
        try (var response = refreshToken(clientId, newRefresh)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }
    }

    @Test
    @DisplayName("refresh token reuse after the grace window revokes the whole family — and it persists")
    void refreshReuseRevokesFamily() {
        String clientId = registerClient();
        Map<String, Object> tokens = mintTokens(clientId);
        String oldRefresh = (String) tokens.get("refresh_token");

        String newAccess;
        String newRefresh;
        try (var response = refreshToken(clientId, oldRefresh)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            Map<String, Object> rotated = response.readEntity(Map.class);
            newAccess = (String) rotated.get("access_token");
            newRefresh = (String) rotated.get("refresh_token");
        }

        // the rotated access token works before the reuse attack
        try (var response = callDataApi(newAccess)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }

        await().pollDelay(ROTATION_GRACE_MS + 500, TimeUnit.MILLISECONDS)
                .atMost(ROTATION_GRACE_MS + 5_000, TimeUnit.MILLISECONDS)
                .until(() -> true);

        // replaying the rotated token after grace is reuse: invalid_grant + family revocation
        try (var response = refreshToken(clientId, oldRefresh)) {
            assertOAuthError(response, "invalid_grant");
        }

        // the revocation must be persisted: every descendant credential is dead
        try (var response = refreshToken(clientId, newRefresh)) {
            assertOAuthError(response, "invalid_grant");
        }
        try (var response = callDataApi(newAccess)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode());
        }
    }

    @Test
    @DisplayName("RFC 7009 revoke of a refresh token kills the family, including the live access token")
    void revokeRefreshTokenKillsFamily() {
        String clientId = registerClient();
        Map<String, Object> tokens = mintTokens(clientId);
        String accessToken = (String) tokens.get("access_token");

        try (var response = client.target(baseURI + "/oauth/revoke")
                .request()
                .post(Entity.form(new Form()
                        .param("token", (String) tokens.get("refresh_token"))
                        .param("client_id", clientId)))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        }

        try (var response = callDataApi(accessToken)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode());
        }
        try (var response = refreshToken(clientId, (String) tokens.get("refresh_token"))) {
            assertOAuthError(response, "invalid_grant");
        }
    }

    @Test
    @DisplayName("/opik/auth-oauth introspection: valid bearer returns identity; garbage returns 401")
    void validateEndpoint() {
        String clientId = registerClient();
        Map<String, Object> tokens = mintTokens(clientId);

        try (var response = client.target(baseURI + "/opik/auth-oauth")
                .request()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get("access_token"))
                .post(Entity.json(""))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            Map<String, Object> body = response.readEntity(Map.class);
            assertThat(body.get("workspace_name")).isEqualTo(DEFAULT_WORKSPACE_NAME);
        }

        try (var response = client.target(baseURI + "/opik/auth-oauth")
                .request()
                .header(HttpHeaders.AUTHORIZATION, "Bearer opik_at_garbage")
                .post(Entity.json(""))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode());
        }
    }

    @Test
    @DisplayName("data API rejects an unknown opik_at_ bearer token when OAuth is enabled")
    void dataApiRejectsUnknownBearer() {
        try (var response = callDataApi("opik_at_definitely-not-a-real-token")) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode());
        }
    }
}
