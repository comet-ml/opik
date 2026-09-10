package com.comet.opik.domain.mcpoauth;

import com.comet.opik.api.resources.oauth.ClientRegistrationResponse;
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

import java.util.List;
import java.util.Set;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.REGISTER_PATH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives Dynamic Client Registration (RFC 7591) against the app and MySQL. The mocked resource test covers the
 * wire shape of a hand-built DTO; this one proves the RFC 7591 §2 software metadata survives the round trip to
 * the row, which is what a connected-clients UI reads back.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@DisplayName("MCP OAuth Client Registration Integration Test")
class McpOAuthClientRegistrationIntegrationTest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, CLICKHOUSE, MYSQL, ZOOKEEPER).join();
        var daf = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder().jdbcUrl(MYSQL.getJdbcUrl()).databaseAnalyticsFactory(daf)
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("mcpOAuth.enabled", "true"),
                                new CustomConfig("mcpOAuth.baseUrl", "http://localhost:8080"),
                                new CustomConfig("mcpOAuth.mcpResourceUri", RESOURCE_URI)))
                        .build());
    }

    private static final String REDIRECT_URI = "http://127.0.0.1:1234/callback";
    private static final String RESOURCE_URI = "http://localhost:8080/api/v1/mcp";

    private ClientSupport client;
    private String baseURI;
    private TransactionTemplate tx;
    private McpOAuthService mcpOAuthService;
    private OAuthClientService clientService;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, TransactionTemplate transactionTemplate,
            McpOAuthService mcpOAuthService, OAuthClientService clientService) {
        this.client = clientSupport;
        this.baseURI = TestUtils.getBaseUrl(clientSupport);
        this.tx = transactionTemplate;
        this.mcpOAuthService = mcpOAuthService;
        this.clientService = clientService;
    }

    @Test
    @DisplayName("registration persists and echoes the RFC 7591 software metadata")
    void registrationPersistsAndEchoesSoftwareMetadata() {
        var request = ClientRegistrationRequest.builder()
                .clientName("Claude Code")
                .redirectUris(Set.of("http://127.0.0.1:1234/callback"))
                .logoUri("https://example.test/logo.png")
                .softwareId("anthropic-claude-code")
                .softwareVersion("2.1.3")
                .clientUri("https://claude.com/claude-code")
                .build();

        String clientId;
        try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
            assertThat(response.getStatus()).isEqualTo(201);
            var body = response.readEntity(ClientRegistrationResponse.class);
            // RFC 7591 3.2.1: registered metadata is echoed back
            assertThat(body.softwareId()).isEqualTo("anthropic-claude-code");
            assertThat(body.softwareVersion()).isEqualTo("2.1.3");
            assertThat(body.clientUri()).isEqualTo("https://claude.com/claude-code");
            clientId = body.clientId();
        }

        var stored = tx.inTransaction(h -> h.attach(McpOAuthClientDAO.class).findActiveById(clientId))
                .orElseThrow();
        assertThat(stored.softwareId()).as("persisted, not dropped").isEqualTo("anthropic-claude-code");
        assertThat(stored.softwareVersion()).isEqualTo("2.1.3");
        assertThat(stored.clientUri()).isEqualTo("https://claude.com/claude-code");
        assertThat(stored.name()).isEqualTo("Claude Code");
        assertThat(stored.logoUri()).isEqualTo("https://example.test/logo.png");
    }

    @Test
    @DisplayName("a script-scheme display URL is dropped, not stored")
    void scriptSchemeDisplayUrlsAreDropped() {
        var request = ClientRegistrationRequest.builder()
                .clientName("Evil Host")
                .redirectUris(Set.of("http://127.0.0.1:4321/cb"))
                .logoUri("javascript:alert(1)")
                .clientUri("  DATA:text/html;base64,PHNjcmlwdD4=")
                .softwareId("evil\r\nFAKE LOG LINE")
                .build();

        try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
            assertThat(response.getStatus()).as("registration still succeeds").isEqualTo(201);
            var body = response.readEntity(ClientRegistrationResponse.class);
            assertThat(body.logoUri()).as("javascript: dropped").isNull();
            assertThat(body.clientUri()).as("data: dropped, whitespace/case not a bypass").isNull();
            assertThat(body.softwareId()).as("control chars stripped").isEqualTo("evil  FAKE LOG LINE");
        }
    }

    @Test
    @DisplayName("an over-long value is truncated, never rejected")
    void overlongValuesAreTruncatedNotRejected() {
        var request = ClientRegistrationRequest.builder()
                .clientName("Verbose Host")
                .redirectUris(Set.of("http://127.0.0.1:4322/cb"))
                .softwareVersion("v".repeat(5000))
                .build();

        try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
            assertThat(response.getStatus()).as("a host that registers today must keep working").isEqualTo(201);
            assertThat(response.readEntity(ClientRegistrationResponse.class).softwareVersion())
                    .hasSize(255);
        }
    }

    @Test
    @DisplayName("a connection made through the client carries its software metadata")
    void connectionCarriesTheClientSoftwareMetadata() {
        // The whole point of keeping the fields: the connection row a UI reads says which product and version.
        var oauth = new OAuthResourceClient(client, baseURI, REDIRECT_URI, RESOURCE_URI);
        var authorized = oauth.authorizeArtifacts(ClientRegistrationRequest.builder()
                .clientName("Claude Code")
                .redirectUris(Set.of(REDIRECT_URI))
                .logoUri("https://example.test/claude.png")
                .softwareId("anthropic-claude-code")
                .softwareVersion("2.1.3")
                .build());
        var resolved = clientService.resolve(authorized.clientId()).orElseThrow();
        var exchange = mcpOAuthService.exchangeCode(authorized.code(), authorized.codeVerifier(), REDIRECT_URI,
                resolved);

        var row = tx.inTransaction(h -> h.attach(McpClientConnectionDAO.class)
                .findByUser(exchange.tokens().workspaceId(), exchange.userName())).stream()
                .filter(c -> c.clientId().equals(authorized.clientId()))
                .findFirst().orElseThrow();

        assertThat(row.clientName()).isEqualTo("Claude Code");
        assertThat(row.softwareId()).isEqualTo("anthropic-claude-code");
        assertThat(row.softwareVersion()).isEqualTo("2.1.3");
        assertThat(row.logoUri()).isEqualTo("https://example.test/claude.png");
    }

    @Test
    @DisplayName("a connection made through a client without software metadata leaves those fields null")
    void connectionWithoutSoftwareMetadataLeavesFieldsNull() {
        var oauth = new OAuthResourceClient(client, baseURI, REDIRECT_URI, RESOURCE_URI);
        var authorized = oauth.authorizeArtifacts("Legacy Host");
        var resolved = clientService.resolve(authorized.clientId()).orElseThrow();
        var exchange = mcpOAuthService.exchangeCode(authorized.code(), authorized.codeVerifier(), REDIRECT_URI,
                resolved);

        var row = tx.inTransaction(h -> h.attach(McpClientConnectionDAO.class)
                .findByUser(exchange.tokens().workspaceId(), exchange.userName())).stream()
                .filter(c -> c.clientId().equals(authorized.clientId()))
                .findFirst().orElseThrow();

        assertThat(row.softwareId()).isNull();
        assertThat(row.softwareVersion()).isNull();
    }

    @Test
    @DisplayName("the software metadata stays optional")
    void registrationWithoutSoftwareMetadataStillWorks() {
        var request = ClientRegistrationRequest.builder()
                .clientName("Legacy Host")
                .redirectUris(Set.of("http://127.0.0.1:9999/cb"))
                .build();

        try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
            assertThat(response.getStatus()).as("optional fields stay optional").isEqualTo(201);
            var body = response.readEntity(ClientRegistrationResponse.class);
            assertThat(body.softwareId()).isNull();
            assertThat(body.softwareVersion()).isNull();
        }
    }
}
