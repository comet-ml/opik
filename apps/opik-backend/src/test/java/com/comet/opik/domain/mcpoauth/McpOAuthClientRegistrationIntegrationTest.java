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
import com.comet.opik.domain.mcpoauth.ClientRegistrationRequest.ClientRegistrationRequestBuilder;
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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
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

    private static final String URL_PREFIX = "https://example.test/";

    /**
     * One of the four optional metadata fields: how to set it on a registration, how to read it back from the
     * response and from the persisted row, the cap its column imposes, and how to build a value of a given
     * length that is valid for it — text takes any filler, a URL has to stay parseable.
     */
    private record MetadataField(String name, int limit, IntFunction<String> sample,
            BiFunction<ClientRegistrationRequestBuilder, String, ClientRegistrationRequestBuilder> set,
            Function<ClientRegistrationResponse, String> echoed, Function<McpOAuthClient, String> stored) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final IntFunction<String> TEXT_SAMPLE = length -> "v".repeat(length);
    private static final IntFunction<String> URL_SAMPLE = length -> URL_PREFIX
            + "p".repeat(length - URL_PREFIX.length());

    private static final List<MetadataField> METADATA_FIELDS = List.of(
            new MetadataField("software_id", 255, TEXT_SAMPLE, ClientRegistrationRequestBuilder::softwareId,
                    ClientRegistrationResponse::softwareId, McpOAuthClient::softwareId),
            new MetadataField("software_version", 255, TEXT_SAMPLE, ClientRegistrationRequestBuilder::softwareVersion,
                    ClientRegistrationResponse::softwareVersion, McpOAuthClient::softwareVersion),
            new MetadataField("client_uri", 2048, URL_SAMPLE, ClientRegistrationRequestBuilder::clientUri,
                    ClientRegistrationResponse::clientUri, McpOAuthClient::clientUri),
            new MetadataField("logo_uri", 2048, URL_SAMPLE, ClientRegistrationRequestBuilder::logoUri,
                    ClientRegistrationResponse::logoUri, McpOAuthClient::logoUri));

    Stream<Arguments> metadataFields() {
        return METADATA_FIELDS.stream().map(Arguments::of);
    }

    /** Both sides of each cap: the last length that fits, and the one before it. */
    Stream<Arguments> metadataFieldsWithinTheirCap() {
        return METADATA_FIELDS.stream()
                .flatMap(field -> Stream.of(Arguments.of(field, field.limit()),
                        Arguments.of(field, field.limit() - 1)));
    }

    /** Registers a client carrying one metadata value; returns what the response echoed and what the row kept. */
    private Map.Entry<String, String> register(MetadataField field, String sent, String host) {
        var request = field.set()
                .apply(ClientRegistrationRequest.builder().clientName(host).redirectUris(Set.of(REDIRECT_URI)), sent)
                .build();

        String clientId;
        String echoed;
        try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
            assertThat(response.getStatus()).as("a host that registers today must keep working").isEqualTo(201);
            var body = response.readEntity(ClientRegistrationResponse.class);
            echoed = field.echoed().apply(body);
            clientId = body.clientId();
        }

        var row = tx.inTransaction(READ_ONLY, h -> h.attach(McpOAuthClientDAO.class).findActiveById(clientId))
                .orElseThrow();
        return Map.entry(echoed, field.stored().apply(row));
    }

    @ParameterizedTest(name = "an over-long {0} is truncated, never rejected")
    @MethodSource("metadataFields")
    void overlongValuesAreTruncatedNotRejected(MetadataField field) {
        String sent = field.sample().apply(field.limit() + 200);
        // Built without the production truncation helper, so a shared bug cannot make both sides agree.
        String expected = sent.substring(0, field.limit());

        var actual = register(field, sent, "Verbose Host");

        assertThat(actual.getKey()).as("echoed %s", field).isEqualTo(expected).hasSize(field.limit());
        assertThat(actual.getValue()).as("persisted %s", field).isEqualTo(expected);
    }

    @ParameterizedTest(name = "a {1}-character {0} is stored unchanged")
    @MethodSource("metadataFieldsWithinTheirCap")
    void valuesWithinTheCapAreStoredUnchanged(MetadataField field, int length) {
        String sent = field.sample().apply(length);

        var actual = register(field, sent, "Exact Host");

        assertThat(actual.getKey()).as("echoed %s", field).isEqualTo(sent);
        assertThat(actual.getValue()).as("persisted %s", field).isEqualTo(sent);
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

    /** The cap landing on the last character of a %XX escape, and on its middle. */
    Stream<Arguments> escapePositions() {
        return Stream.of(Arguments.of("last character of the escape", 1),
                Arguments.of("middle of the escape", 2));
    }

    @ParameterizedTest(name = "a URL capped at the {0} keeps a parseable value")
    @MethodSource("escapePositions")
    void overlongUrlIsTruncatedWithoutSplittingAnEscape(String label, int backOff) {
        // Place "%20" so the 2048-character cut lands inside it: without backing off, the stored value would
        // end in "%" or "%2" and no longer parse.
        String head = URL_PREFIX + "p".repeat(2048 - URL_PREFIX.length() - backOff);
        var request = ClientRegistrationRequest.builder()
                .clientName("Escaping Host")
                .redirectUris(Set.of(REDIRECT_URI))
                .logoUri(head + "%20and-more-beyond-the-cap")
                .build();

        try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
            assertThat(response.getStatus()).isEqualTo(201);
            String echoed = response.readEntity(ClientRegistrationResponse.class).logoUri();
            assertThat(echoed).as("a valid long URL is truncated, never dropped").isNotNull();
            assertThat(echoed.length()).isLessThanOrEqualTo(2048);
            assertThat(echoed).as("no dangling percent-escape").doesNotEndWith("%").doesNotEndWith("%2");
            assertThatCode(() -> new URI(echoed)).as("still parses").doesNotThrowAnyException();
        }
    }

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
