package com.comet.opik.domain.mcpoauth;

import com.comet.opik.api.resources.oauth.AuthorizeContext;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.AUTHORIZE_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REDIRECT_URI;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.REGISTER_PATH;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
                .redirectUris(Set.of(REDIRECT_URI))
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

        var stored = tx.inTransaction(READ_ONLY, h -> h.attach(McpOAuthClientDAO.class).findActiveById(clientId))
                .orElseThrow();
        // The whole row, not spot checks: a field the mapper or the INSERT drops has to fail here.
        assertThat(stored)
                .usingRecursiveComparison()
                .isEqualTo(McpOAuthClient.builder()
                        .id(clientId)
                        .name("Claude Code")
                        .redirectUris(Set.of(REDIRECT_URI))
                        .logoUri("https://example.test/logo.png")
                        .softwareId("anthropic-claude-code")
                        .softwareVersion("2.1.3")
                        .clientUri("https://claude.com/claude-code")
                        .build());
    }

    @Test
    @DisplayName("script- and data-scheme display URLs are dropped, not stored")
    void scriptAndDataDisplayUrlsAreDropped() {
        var request = ClientRegistrationRequest.builder()
                .clientName("Evil Host")
                .redirectUris(Set.of("http://127.0.0.1:4321/cb"))
                .logoUri("javascript:alert(1)")
                .clientUri("  DATA:text/html;base64,PHNjcmlwdD4=")
                .softwareId("evil\r\nFAKE LOG LINE")
                .softwareVersion("1.0\u2028FORGED\u2029LINES")
                .build();

        String clientId;
        try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
            assertThat(response.getStatus()).as("registration still succeeds").isEqualTo(201);
            var body = response.readEntity(ClientRegistrationResponse.class);
            assertThat(body.logoUri()).as("javascript: dropped").isNull();
            assertThat(body.clientUri()).as("data: dropped, whitespace/case not a bypass").isNull();
            assertThat(body.softwareId()).as("control chars stripped").isEqualTo("evil  FAKE LOG LINE");
            assertThat(body.softwareVersion()).as("Unicode line/paragraph separators stripped too")
                    .isEqualTo("1.0 FORGED LINES");
            clientId = body.clientId();
        }

        // The echo is built from the same object that was written, but only the row proves nothing unsafe
        // reached storage — that is what the consent page and the connected-clients UI read.
        var stored = tx.inTransaction(READ_ONLY, h -> h.attach(McpOAuthClientDAO.class).findActiveById(clientId))
                .orElseThrow();
        assertThat(stored.logoUri()).as("persisted logo_uri").isNull();
        assertThat(stored.clientUri()).as("persisted client_uri").isNull();
        assertThat(stored.softwareId()).as("persisted software_id").isEqualTo("evil  FAKE LOG LINE");
    }

    @Test
    @DisplayName("a malformed or host-less http URL is dropped like a script-scheme one")
    void malformedDisplayUrlsAreDropped() {
        var request = ClientRegistrationRequest.builder()
                .clientName("Sloppy Host")
                .redirectUris(Set.of("http://127.0.0.1:4323/cb"))
                .logoUri("http://[bad-host/logo.png")
                .clientUri("http:///no-host")
                .build();

        try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
            assertThat(response.getStatus()).as("registration still succeeds").isEqualTo(201);
            var body = response.readEntity(ClientRegistrationResponse.class);
            assertThat(body.logoUri()).as("unparseable URL dropped").isNull();
            assertThat(body.clientUri()).as("URL without a host dropped").isNull();
        }
    }

    /** The other side of truncation: a value that fits, at and just under each cap, must survive untouched. */
    Stream<Arguments> valuesWithinTheCap() {
        return Stream.of(
                Arguments.of("software_version at the cap", 255,
                        (IntFunction<String>) n -> "v".repeat(n),
                        (UnaryOperator<ClientRegistrationRequest.ClientRegistrationRequestBuilder>) b -> b,
                        true),
                Arguments.of("software_version just under the cap", 254,
                        (IntFunction<String>) n -> "v".repeat(n),
                        (UnaryOperator<ClientRegistrationRequest.ClientRegistrationRequestBuilder>) b -> b,
                        true),
                Arguments.of("logo_uri at the cap", 2048,
                        (IntFunction<String>) n -> "https://example.test/" + "p".repeat(n - 21),
                        (UnaryOperator<ClientRegistrationRequest.ClientRegistrationRequestBuilder>) b -> b,
                        false),
                Arguments.of("logo_uri just under the cap", 2047,
                        (IntFunction<String>) n -> "https://example.test/" + "p".repeat(n - 21),
                        (UnaryOperator<ClientRegistrationRequest.ClientRegistrationRequestBuilder>) b -> b,
                        false));
    }

    @ParameterizedTest(name = "{0} is stored unchanged")
    @MethodSource("valuesWithinTheCap")
    void valuesWithinTheCapAreStoredUnchanged(String label, int length, IntFunction<String> build,
            UnaryOperator<ClientRegistrationRequest.ClientRegistrationRequestBuilder> unused, boolean isText) {
        String sent = build.apply(length);
        var builder = ClientRegistrationRequest.builder()
                .clientName("Exact Host")
                .redirectUris(Set.of("http://127.0.0.1:4326/cb"));
        var request = (isText ? builder.softwareVersion(sent) : builder.logoUri(sent)).build();

        String clientId;
        try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
            assertThat(response.getStatus()).isEqualTo(201);
            var body = response.readEntity(ClientRegistrationResponse.class);
            assertThat(isText ? body.softwareVersion() : body.logoUri()).as("echoed %s", label).isEqualTo(sent);
            clientId = body.clientId();
        }

        var row = tx.inTransaction(READ_ONLY, h -> h.attach(McpOAuthClientDAO.class).findActiveById(clientId))
                .orElseThrow();
        assertThat(isText ? row.softwareVersion() : row.logoUri()).as("persisted %s", label).isEqualTo(sent);
    }

    @Test
    @DisplayName("a display URL carrying credentials is dropped, not persisted")
    void displayUrlsWithCredentialsAreDropped() {
        var request = ClientRegistrationRequest.builder()
                .clientName("Leaky Host")
                .redirectUris(Set.of("http://127.0.0.1:4324/cb"))
                .logoUri("https://user:s3cr3t@example.test/logo.png")
                .build();

        String clientId;
        try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
            assertThat(response.getStatus()).as("registration still succeeds").isEqualTo(201);
            var body = response.readEntity(ClientRegistrationResponse.class);
            assertThat(body.logoUri()).as("credentials never echoed").isNull();
            clientId = body.clientId();
        }

        var stored = tx.inTransaction(READ_ONLY, h -> h.attach(McpOAuthClientDAO.class).findActiveById(clientId))
                .orElseThrow();
        assertThat(stored.logoUri()).as("credentials never persisted").isNull();
    }

    @Test
    @DisplayName("an over-long URL is capped without leaving a half-written percent-escape")
    void overlongUrlIsTruncatedWithoutSplittingAnEscape() {
        // The cap lands mid-escape: without backing off, the stored value ends in "%2" or "%" and no longer parses.
        String tail = "%20end";
        String padded = "https://example.test/" + "p".repeat(2048 - "https://example.test/".length() - 1)
                + tail;
        var request = ClientRegistrationRequest.builder()
                .clientName("Escaping Host")
                .redirectUris(Set.of("http://127.0.0.1:4325/cb"))
                .logoUri(padded)
                .build();

        try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
            assertThat(response.getStatus()).isEqualTo(201);
            String echoed = response.readEntity(ClientRegistrationResponse.class).logoUri();
            assertThat(echoed).as("a valid long URL is truncated, never dropped").isNotNull();
            assertThat(echoed.length()).isLessThanOrEqualTo(2048);
            assertThat(echoed).as("no dangling percent-escape").doesNotEndWith("%").doesNotEndWith("%2");
            assertThatCode(() -> new java.net.URI(echoed)).as("still parses").doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a row stored before the filters existed is cleaned on the way to the consent page")
    void legacyUnsafeRowIsCleanedOnRead() {
        // Written straight through the DAO, as a registration from before the write-side filters would have been:
        // a script-scheme logo, a data: URL, and a name with a forged log line in it.
        String clientId = UUID.randomUUID().toString();
        tx.inTransaction(WRITE, h -> {
            h.attach(McpOAuthClientDAO.class).save(McpOAuthClient.builder()
                    .id(clientId)
                    .name("Legacy Evil Host\r\nFAKE LOG LINE")
                    .redirectUris(Set.of(REDIRECT_URI))
                    .logoUri("javascript:alert(1)")
                    .clientUri("data:text/html;base64,PHNjcmlwdD4=")
                    .build());
            return null;
        });

        var resolved = clientService.resolve(clientId).orElseThrow();
        assertThat(resolved.name()).as("resolved name").isEqualTo("Legacy Evil Host  FAKE LOG LINE");
        assertThat(resolved.logoUri()).as("resolved logo_uri").isNull();
        assertThat(resolved.clientUri()).as("resolved client_uri").isNull();

        try (var response = client.target(baseURI + AUTHORIZE_PATH + "/context")
                .queryParam(PARAM_CLIENT_ID, clientId)
                .queryParam(PARAM_REDIRECT_URI, REDIRECT_URI)
                .request().get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            var context = response.readEntity(AuthorizeContext.class);
            assertThat(context.clientName()).as("the name the consent page renders")
                    .isEqualTo("Legacy Evil Host  FAKE LOG LINE");
            assertThat(context.clientLogoUri()).as("the <img src> the consent page would render").isNull();
        }
    }

    /**
     * One case per optional metadata field (the two text ones and the two URLs): the over-long value a host could
     * send, and the cap the column imposes.
     */
    Stream<Arguments> overlongMetadataValues() {
        String text = "v".repeat(5000);
        String uri = "https://example.test/" + "p".repeat(5000);
        return Stream.of(
                Arguments.of("software_id", text, 255,
                        (UnaryOperator<ClientRegistrationRequest.ClientRegistrationRequestBuilder>) b -> b
                                .softwareId(text),
                        (Function<ClientRegistrationResponse, String>) ClientRegistrationResponse::softwareId,
                        (Function<McpOAuthClient, String>) McpOAuthClient::softwareId),
                Arguments.of("software_version", text, 255,
                        (UnaryOperator<ClientRegistrationRequest.ClientRegistrationRequestBuilder>) b -> b
                                .softwareVersion(text),
                        (Function<ClientRegistrationResponse, String>) ClientRegistrationResponse::softwareVersion,
                        (Function<McpOAuthClient, String>) McpOAuthClient::softwareVersion),
                Arguments.of("client_uri", uri, 2048,
                        (UnaryOperator<ClientRegistrationRequest.ClientRegistrationRequestBuilder>) b -> b
                                .clientUri(uri),
                        (Function<ClientRegistrationResponse, String>) ClientRegistrationResponse::clientUri,
                        (Function<McpOAuthClient, String>) McpOAuthClient::clientUri),
                Arguments.of("logo_uri", uri, 2048,
                        (UnaryOperator<ClientRegistrationRequest.ClientRegistrationRequestBuilder>) b -> b.logoUri(uri),
                        (Function<ClientRegistrationResponse, String>) ClientRegistrationResponse::logoUri,
                        (Function<McpOAuthClient, String>) McpOAuthClient::logoUri));
    }

    @ParameterizedTest(name = "{0} over {2} characters is truncated, never rejected")
    @MethodSource("overlongMetadataValues")
    void overlongValuesAreTruncatedNotRejected(String field, String sent, int limit,
            UnaryOperator<ClientRegistrationRequest.ClientRegistrationRequestBuilder> withValue,
            Function<ClientRegistrationResponse, String> echoed, Function<McpOAuthClient, String> stored) {
        var request = withValue.apply(ClientRegistrationRequest.builder()
                .clientName("Verbose Host")
                .redirectUris(Set.of("http://127.0.0.1:4322/cb")))
                .build();
        // Built without the production truncation helper, so a shared bug cannot make both sides agree.
        String expected = sent.substring(0, limit);

        String clientId;
        try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
            assertThat(response.getStatus()).as("a host that registers today must keep working").isEqualTo(201);
            var body = response.readEntity(ClientRegistrationResponse.class);
            assertThat(echoed.apply(body)).as("echoed %s", field).isEqualTo(expected).hasSize(limit);
            clientId = body.clientId();
        }

        var row = tx.inTransaction(READ_ONLY, h -> h.attach(McpOAuthClientDAO.class).findActiveById(clientId))
                .orElseThrow();
        assertThat(stored.apply(row)).as("persisted %s", field).isEqualTo(expected);
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

        var row = tx.inTransaction(READ_ONLY, h -> h.attach(McpClientConnectionDAO.class)
                .findByUser(exchange.tokens().workspaceId(), exchange.userName())).stream()
                .filter(c -> c.clientId().equals(authorized.clientId()))
                .findFirst().orElseThrow();

        assertThat(row)
                .usingRecursiveComparison()
                .ignoringFields("id", "firstConnectedAt", "lastConnectedAt", "active")
                .isEqualTo(McpClientConnection.builder()
                        .id(row.id())
                        .userName(exchange.userName())
                        .workspaceName(exchange.tokens().workspaceName())
                        .workspaceId(exchange.tokens().workspaceId())
                        .clientId(authorized.clientId())
                        .clientName("Claude Code")
                        .softwareId("anthropic-claude-code")
                        .softwareVersion("2.1.3")
                        .logoUri("https://example.test/claude.png")
                        .resource(RESOURCE_URI)
                        .redirectUri(REDIRECT_URI)
                        .build());
    }

    @Test
    @DisplayName("a connection made through a client without software metadata leaves those fields null")
    void connectionWithoutSoftwareMetadataLeavesFieldsNull() {
        var oauth = new OAuthResourceClient(client, baseURI, REDIRECT_URI, RESOURCE_URI);
        var authorized = oauth.authorizeArtifacts("Legacy Host");
        var resolved = clientService.resolve(authorized.clientId()).orElseThrow();
        var exchange = mcpOAuthService.exchangeCode(authorized.code(), authorized.codeVerifier(), REDIRECT_URI,
                resolved);

        var row = tx.inTransaction(READ_ONLY, h -> h.attach(McpClientConnectionDAO.class)
                .findByUser(exchange.tokens().workspaceId(), exchange.userName())).stream()
                .filter(c -> c.clientId().equals(authorized.clientId()))
                .findFirst().orElseThrow();

        // Same shape as above with the three metadata fields absent: what is unchecked is visible.
        assertThat(row)
                .usingRecursiveComparison()
                .ignoringFields("id", "firstConnectedAt", "lastConnectedAt", "active")
                .isEqualTo(McpClientConnection.builder()
                        .id(row.id())
                        .userName(exchange.userName())
                        .workspaceName(exchange.tokens().workspaceName())
                        .workspaceId(exchange.tokens().workspaceId())
                        .clientId(authorized.clientId())
                        .clientName("Legacy Host")
                        .softwareId(null)
                        .softwareVersion(null)
                        .logoUri(null)
                        .resource(RESOURCE_URI)
                        .redirectUri(REDIRECT_URI)
                        .build());
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
