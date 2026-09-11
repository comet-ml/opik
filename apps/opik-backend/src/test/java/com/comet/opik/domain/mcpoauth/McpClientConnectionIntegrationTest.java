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
import com.comet.opik.domain.ProjectService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import org.apache.commons.lang3.RandomStringUtils;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.REVOKE_PATH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lifecycle of a connection row: an exchange creates it, a revocation of the whole grant removes it, and
 * a row whose tokens are gone reads as inactive. Together these are what lets a connected-clients UI say
 * whether a client is still attached, and what makes reconnecting after a revocation count as new.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@DisplayName("MCP Client Connection Integration Test")
class McpClientConnectionIntegrationTest {

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

    private ClientSupport client;
    private String baseURI;
    private TransactionTemplate transactionTemplate;
    private OAuthResourceClient oauthClient;
    private McpOAuthService mcpOAuthService;
    private OAuthClientService clientService;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, TransactionTemplate transactionTemplate,
            McpOAuthService mcpOAuthService, OAuthClientService clientService) {
        this.client = clientSupport;
        this.baseURI = TestUtils.getBaseUrl(clientSupport);
        this.transactionTemplate = transactionTemplate;
        this.oauthClient = new OAuthResourceClient(clientSupport, baseURI, REDIRECT_URI, RESOURCE_URI);
        this.mcpOAuthService = mcpOAuthService;
        this.clientService = clientService;
    }

    /** Drives the exchange through the service so the test can see what it decided about first connection. */
    private CodeExchange exchange(String clientName) {
        return exchange(oauthClient.authorizeArtifacts(clientName));
    }

    private CodeExchange exchange(OAuthResourceClient.Authorized authorized) {
        var resolved = clientService.resolve(authorized.clientId()).orElseThrow();
        return mcpOAuthService.exchangeCode(authorized.code(), authorized.codeVerifier(), REDIRECT_URI, resolved);
    }

    /** The client_id behind an exchange: CodeExchange does not carry it, the connection row does. */
    private String oauthClientIdOf(CodeExchange exchange) {
        return connections(exchange.tokens().workspaceId(), exchange.userName()).stream()
                .filter(McpClientConnection::active)
                .max(java.util.Comparator.comparing(McpClientConnection::lastConnectedAt))
                .orElseThrow()
                .clientId();
    }

    /** The silent disconnect: the host stops coming back and its tokens age out, but its row stays. */
    private void expireTokensOf(String clientId) {
        transactionTemplate.inTransaction(handle -> handle
                .createUpdate("UPDATE mcp_oauth_tokens SET expires_at = NOW(6) - INTERVAL 1 SECOND "
                        + "WHERE client_id = :clientId")
                .bind("clientId", clientId)
                .execute());
    }

    private List<McpClientConnection> connections(String workspaceId, String userName) {
        return transactionTemplate
                .inTransaction(handle -> handle.attach(McpClientConnectionDAO.class)
                        .findByUser(workspaceId, userName));
    }

    /**
     * Every test here consents as the same user in the same default workspace, so the rows accumulate.
     * Assertions scope to the client the test itself registered.
     */
    private Optional<McpClientConnection> connection(String workspaceId, String userName, String clientId) {
        return connections(workspaceId, userName).stream()
                .filter(row -> row.clientId().equals(clientId))
                .findFirst();
    }

    private void revoke(String token) {
        try (var response = client.target(baseURI + REVOKE_PATH).request()
                .post(Entity.form(new Form().param(PARAM_TOKEN, token)
                        .param(PARAM_CLIENT_ID, UUID.randomUUID().toString())))) {
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("an exchange records the whole connection, and it reports active while its tokens live")
    void exchangeRecordsAnActiveConnection() {
        String host = "Host-" + RandomStringUtils.secure().nextAlphanumeric(6);
        var authorized = oauthClient.authorizeArtifacts(host);
        var exchange = exchange(authorized);

        var row = connection(exchange.tokens().workspaceId(), ProjectService.DEFAULT_USER, authorized.clientId())
                .orElseThrow();

        // The row is what a connected-clients UI renders: every field it carries has to survive the mapper
        // and the upsert, not just the identity.
        assertThat(row.userName()).isEqualTo(ProjectService.DEFAULT_USER);
        assertThat(row.workspaceId()).isEqualTo(exchange.tokens().workspaceId());
        assertThat(row.workspaceName()).isEqualTo(exchange.tokens().workspaceName());
        assertThat(row.clientId()).isEqualTo(authorized.clientId());
        assertThat(row.clientName()).isEqualTo(host);
        assertThat(row.logoUri()).as("registration sent no logo").isNull();
        assertThat(row.resource()).isEqualTo(RESOURCE_URI);
        assertThat(row.redirectUri()).isEqualTo(REDIRECT_URI);
        assertThat(row.firstConnectedAt()).isNotNull();
        assertThat(row.lastConnectedAt()).isAfterOrEqualTo(row.firstConnectedAt());
        assertThat(row.active()).as("tokens are live").isTrue();
    }

    @Test
    @DisplayName("revoking one of two live grants of the same client_id keeps the connection")
    void revokingOneOfTwoLiveGrantsKeepsTheConnection() {
        // A host that re-authorizes while its previous grant is still live holds two families under one
        // client_id. Revoking one of them is not a disconnection: the other still works.
        var authorized = oauthClient.authorizeArtifacts("Host-" + RandomStringUtils.secure().nextAlphanumeric(6));
        var first = exchange(authorized);
        exchange(oauthClient.reauthorize(authorized.clientId()));

        revoke(first.tokens().refreshToken());

        var row = connection(first.tokens().workspaceId(), ProjectService.DEFAULT_USER, authorized.clientId());
        assertThat(row).as("the second grant still carries the connection").isPresent();
        assertThat(row.orElseThrow().active()).isTrue();
    }

    @Test
    @DisplayName("revoking the refresh token removes the connection, so reconnecting counts as new")
    void revokingTheRefreshTokenRemovesTheConnection() {
        var minted = oauthClient.mintArtifacts();
        String workspaceId = minted.tokens().workspaceId();
        assertThat(connection(workspaceId, ProjectService.DEFAULT_USER, minted.clientId())).isPresent();

        revoke(minted.tokens().refreshToken());

        assertThat(connection(workspaceId, ProjectService.DEFAULT_USER, minted.clientId()))
                .as("the whole grant is gone, so the connection is too")
                .isEmpty();
    }

    @Test
    @DisplayName("revoking only an access token keeps the connection: the grant survives")
    void revokingOnlyAnAccessTokenKeepsTheConnection() {
        var minted = oauthClient.mintArtifacts();
        String workspaceId = minted.tokens().workspaceId();

        revoke(minted.tokens().accessToken());

        assertThat(connection(workspaceId, ProjectService.DEFAULT_USER, minted.clientId()))
                .as("the refresh token still carries the grant")
                .isPresent();
    }

    @Test
    @DisplayName("a second registration of the same host by the same user is not a new connection")
    void secondRegistrationOfTheSameHostIsNotANewConnection() {
        // Codex keeps one registration per project, Cursor re-registered on every reconnect for months: the
        // same human ends up with several client_ids for one product. That is one adoption, not several.
        String host = "Codex-" + RandomStringUtils.secure().nextAlphanumeric(6);

        var first = exchange(host);
        var second = exchange(host);

        assertThat(first.firstConnection()).as("first client_id for this host").isTrue();
        assertThat(second.firstConnection()).as("another client_id, same host, still connected").isFalse();
        assertThat(connections(first.tokens().workspaceId(), ProjectService.DEFAULT_USER))
                .filteredOn(row -> row.clientName().equals(host))
                .as("both registrations are still recorded for the UI").hasSize(2);
    }

    @Test
    @DisplayName("a different host is a new connection even while another host is connected")
    void aDifferentHostIsANewConnection() {
        String suffix = RandomStringUtils.secure().nextAlphanumeric(6);

        var first = exchange("Claude Code-" + suffix);
        var second = exchange("Cursor-" + suffix);

        assertThat(first.firstConnection()).isTrue();
        assertThat(second.firstConnection()).as("different product, own activation").isTrue();
    }

    @Test
    @DisplayName("once a host's tokens have aged out, a fresh registration of it is a new connection")
    void aHostWhoseTokensAgedOutIsNewAgainUnderANewClientId() {
        String host = "OpenCode-" + RandomStringUtils.secure().nextAlphanumeric(6);
        var first = exchange(host);
        assertThat(first.firstConnection()).isTrue();

        // Nothing deleted the row: the user just stopped using the host until its grant lapsed.
        expireTokensOf(oauthClientIdOf(first));
        var second = exchange(host);

        assertThat(second.firstConnection()).as("the user left and came back").isTrue();
    }

    @Test
    @DisplayName("once a host's tokens have aged out, reconnecting on the same client_id is a new connection")
    void aHostWhoseTokensAgedOutIsNewAgainOnTheSameClientId() {
        // Claude Code, VS Code and claude.ai reuse one registration for weeks, so their return arrives on the
        // client_id already in the table. That must count as a new connection too, or they never count again.
        var authorized = oauthClient
                .authorizeArtifacts("Claude Code-" + RandomStringUtils.secure().nextAlphanumeric(6));
        var first = exchange(authorized);
        assertThat(first.firstConnection()).isTrue();

        expireTokensOf(authorized.clientId());
        var second = exchange(oauthClient.reauthorize(authorized.clientId()));

        assertThat(second.firstConnection()).as("same client_id, but nothing of it was live").isTrue();
    }

    @Test
    @DisplayName("reconnecting on the same client_id while its grant is live is not a new connection")
    void reconnectingOnTheSameClientIdWhileLiveIsNotNew() {
        var authorized = oauthClient.authorizeArtifacts("VS Code-" + RandomStringUtils.secure().nextAlphanumeric(6));
        exchange(authorized);

        var again = exchange(oauthClient.reauthorize(authorized.clientId()));

        assertThat(again.firstConnection()).as("forced re-authorization of a live grant").isFalse();
    }

    @Test
    @DisplayName("revoking the grant and coming back is a new connection")
    void revokingAndComingBackIsANewConnection() {
        String host = "Cursor-" + RandomStringUtils.secure().nextAlphanumeric(6);
        var first = exchange(host);
        revoke(first.tokens().refreshToken());

        var second = exchange(host);

        assertThat(second.firstConnection()).isTrue();
    }

    @Test
    @DisplayName("a connection whose tokens are gone reports inactive")
    void connectionWithoutLiveTokensReportsInactive() {
        // The client that was simply deleted on the user's machine: it never tells us, it just stops coming
        // back, and its tokens age out. Nothing deletes the row, so the UI needs to see it as inactive.
        String userName = "u-" + RandomStringUtils.secure().nextAlphanumeric(8);
        String workspaceId = "ws-" + RandomStringUtils.secure().nextAlphanumeric(8);

        transactionTemplate.inTransaction(handle -> handle.attach(McpClientConnectionDAO.class)
                .upsert(McpClientConnection.builder()
                        .id(UUID.randomUUID().toString())
                        .userName(userName)
                        .workspaceName("ws-name")
                        .workspaceId(workspaceId)
                        .clientId(UUID.randomUUID().toString())
                        .clientName("Abandoned Host")
                        .resource(RESOURCE_URI)
                        .redirectUri(REDIRECT_URI)
                        .build()));

        var rows = connections(workspaceId, userName);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().active()).as("no live token backs it").isFalse();
    }

    @Test
    @DisplayName("an expired token does not keep a connection active")
    void expiredTokensDoNotKeepAConnectionActive() {
        String userName = "u-" + RandomStringUtils.secure().nextAlphanumeric(8);
        String workspaceId = "ws-" + RandomStringUtils.secure().nextAlphanumeric(8);
        String clientId = UUID.randomUUID().toString();

        transactionTemplate.inTransaction(handle -> {
            handle.attach(McpClientConnectionDAO.class).upsert(McpClientConnection.builder()
                    .id(UUID.randomUUID().toString()).userName(userName).workspaceName("ws-name")
                    .workspaceId(workspaceId).clientId(clientId).clientName("Stale Host")
                    .resource(RESOURCE_URI).redirectUri(REDIRECT_URI).build());
            handle.attach(McpOAuthTokenDAO.class).save(McpOAuthToken.builder()
                    .id(UUID.randomUUID().toString())
                    .tokenHash(RandomStringUtils.secure().nextAlphanumeric(64))
                    .type(McpOAuthToken.TYPE_REFRESH).clientId(clientId).userName(userName)
                    .workspaceName("ws-name").workspaceId(workspaceId).resource(RESOURCE_URI)
                    .familyId(UUID.randomUUID().toString())
                    .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                    .build());
            return null;
        });

        assertThat(connections(workspaceId, userName).getFirst().active())
                .as("an expired token is not a live grant").isFalse();
    }
}
