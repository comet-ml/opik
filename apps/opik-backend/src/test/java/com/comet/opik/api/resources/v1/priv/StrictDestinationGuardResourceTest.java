package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.EncryptedAuthConfig;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.OllamaInstanceBaseUrlRequest;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.ProviderAuthConfig;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.LlmProviderApiKeyResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both destination guards in strict mode, as on Opik Cloud: the endpoints taking a provider destination must refuse
 * non-public ones and leave any stored provider untouched. IP literals keep these cases free of DNS.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Strict destination guard")
@ExtendWith(DropwizardAppExtensionProvider.class)
class StrictDestinationGuardResourceTest {

    private static final String USER = UUID.randomUUID().toString();
    private static final String[] IGNORED_FIELDS = {"createdAt", "lastUpdatedAt", "apiKey"};
    // never contacted: saving a provider only checks where its base_url resolves
    private static final String PUBLIC_BASE_URL = "http://1.1.1.1/v1";
    // spelled out rather than shared with the production code, so a changed message fails here
    private static final ErrorMessage BASE_URL_REFUSED = new ErrorMessage(HttpStatus.SC_BAD_REQUEST,
            "the provider base_url was refused: it must be a valid URL with an allowed scheme that resolves to a"
                    + " public address (self-hosted deployments with providers on internal networks can set"
                    + " LLM_PROVIDER_DESTINATION_GUARD=relaxed)");
    private static final ErrorMessage TOKEN_URL_REFUSED = new ErrorMessage(HttpStatus.SC_BAD_REQUEST,
            "the auth_config token_url was refused: it must be a valid https URL that resolves to a public address"
                    + " (self-hosted deployments with internal auth services can set"
                    + " LLM_PROVIDER_TOKEN_AUTH_DESTINATION_GUARD=relaxed)");

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("llmProviderClient.destinationGuard", "strict"),
                                new CustomConfig("llmProviderTokenAuth.destinationGuard", "strict")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ClientSupport clientSupport;
    private LlmProviderApiKeyResourceClient client;

    private String apiKey;
    private String workspaceName;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport) {
        this.clientSupport = clientSupport;
        this.client = new LlmProviderApiKeyResourceClient(clientSupport);
        ClientSupportUtils.config(clientSupport);
    }

    @BeforeEach
    void setUp() {
        apiKey = UUID.randomUUID().toString();
        workspaceName = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, UUID.randomUUID().toString(),
                USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8081/tasks/log-level", "http://169.254.169.254/latest/meta-data",
            "http://10.0.0.1/v1", "http://10.0.0.1/{model}/v1", "http://{model}@10.0.0.1/v1",
            "http://user:{model}@10.0.0.1/v1",
            // IPv6 forms that embed an IPv4 address: IPv4-compatible, 6to4 and Teredo
            "http://[::a00:1]/v1", "http://[2002:a00:1::1]/v1", "http://[2001:0:4136:e378:8000:63bf:f5ff:fffe]/v1",
            // a {model} host defers only the DNS check, not the scheme
            "ftp://{model}.example.com/v1",
            // the placeholder stand-in written literally is just an unresolvable host (RFC 2606)
            "https://opik-model-a.invalid/v1"})
    @DisplayName("POST refuses a base_url that isn't public http(s), wherever a {model} sits, and stores nothing")
    void createRefusesNonPublicBaseUrl(String baseUrl) {
        try (var response = client.callCreateProviderApiKey(customProvider(baseUrl), apiKey, workspaceName)) {
            assertRefused(response, BASE_URL_REFUSED);
        }

        // the listing can include the virtual free-model provider, so check for this destination only
        assertThat(client.getAll(workspaceName, apiKey).content())
                .extracting(ProviderApiKey::baseUrl)
                .doesNotContain(baseUrl);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8081/tasks/log-level", "http://169.254.169.254/latest/meta-data",
            "http://10.0.0.1/v1", "http://10.0.0.1/{model}/v1", "http://{model}@10.0.0.1/v1"})
    @DisplayName("PATCH refuses a non-public base_url and keeps the stored one")
    void updateRefusesNonPublicBaseUrl(String baseUrl) {
        var created = client.createProviderApiKey(customProvider(PUBLIC_BASE_URL), apiKey, workspaceName,
                HttpStatus.SC_CREATED);
        // credentials resent, so the destination check is the only reason left to refuse
        var update = ProviderApiKeyUpdate.builder().baseUrl(baseUrl).apiKey(UUID.randomUUID().toString()).build();

        try (var response = client.callUpdateProviderApiKey(created.id(), update, apiKey, workspaceName)) {
            assertRefused(response, BASE_URL_REFUSED);
        }

        assertStored(created);
    }

    @Test
    @DisplayName("PATCH of a provider missing from the workspace is 404, before any destination check")
    void updateOfMissingProviderIsNotFound() {
        var update = ProviderApiKeyUpdate.builder()
                .baseUrl("http://10.0.0.1/v1")
                .apiKey(UUID.randomUUID().toString())
                .build();

        try (var response = client.callUpdateProviderApiKey(UUID.randomUUID(), update, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("a {model} in the host is checked as saved for providers whose client doesn't substitute it")
    void refusesHostLevelModelPlaceholderForNonSubstitutingProvider() {
        // .invalid never resolves (RFC 2606), so the stand-in host can't pass the check
        var baseUrl = "https://{model}.invalid/v1";
        var openAi = customProvider(baseUrl).toBuilder().provider(LlmProvider.OPEN_AI).providerName(null).build();

        try (var response = client.callCreateProviderApiKey(openAi, apiKey, workspaceName)) {
            assertRefused(response, BASE_URL_REFUSED);
        }

        var created = client.createProviderApiKey(openAi.toBuilder().baseUrl(PUBLIC_BASE_URL).build(), apiKey,
                workspaceName, HttpStatus.SC_CREATED);
        var update = ProviderApiKeyUpdate.builder().baseUrl(baseUrl).apiKey(UUID.randomUUID().toString()).build();

        try (var response = client.callUpdateProviderApiKey(created.id(), update, apiKey, workspaceName)) {
            assertRefused(response, BASE_URL_REFUSED);
        }

        assertStored(created);
    }

    @Test
    @DisplayName("a {model} in the host is accepted on save: only the substituted request URL can be judged")
    void acceptsHostLevelModelPlaceholder() {
        var baseUrl = "https://{model}.example.com/v1";
        var created = client.createProviderApiKey(customProvider(baseUrl), apiKey, workspaceName,
                HttpStatus.SC_CREATED);
        assertStored(created);

        var update = ProviderApiKeyUpdate.builder()
                .baseUrl("https://{model}.gw.example.com/v1")
                .apiKey(UUID.randomUUID().toString())
                .build();
        client.updateProviderApiKey(created.id(), update, apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

        assertStored(created.toBuilder().baseUrl(update.baseUrl()).build());
    }

    @Test
    @DisplayName("a public base_url is still accepted in strict mode")
    void acceptsPublicBaseUrl() {
        var created = client.createProviderApiKey(customProvider(PUBLIC_BASE_URL), apiKey, workspaceName,
                HttpStatus.SC_CREATED);

        assertStored(created);
    }

    @Test
    @DisplayName("POST and PATCH refuse a non-public auth_config token_url")
    void refusesNonPublicTokenUrl() {
        var internalTokenUrl = "https://127.0.0.1/oauth/token";
        var withInternalTokenUrl = customProvider(PUBLIC_BASE_URL).toBuilder()
                .apiKey(null)
                .authConfig(EncryptedAuthConfig.of(authConfig(internalTokenUrl)))
                .build();

        try (var response = client.callCreateProviderApiKey(withInternalTokenUrl, apiKey, workspaceName)) {
            assertRefused(response, TOKEN_URL_REFUSED);
        }

        var created = client.createProviderApiKey(customProvider(PUBLIC_BASE_URL), apiKey, workspaceName,
                HttpStatus.SC_CREATED);
        var update = ProviderApiKeyUpdate.builder().apiKey("").authConfig(authConfig(internalTokenUrl)).build();

        try (var response = client.callUpdateProviderApiKey(created.id(), update, apiKey, workspaceName)) {
            assertRefused(response, TOKEN_URL_REFUSED);
        }

        assertStored(created);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/private/ollama/test-connection", "/v1/private/ollama/models"})
    @DisplayName("the Ollama endpoints refuse a non-public instance")
    void ollamaRefusesNonPublicInstance(String path) {
        var request = OllamaInstanceBaseUrlRequest.builder().baseUrl("http://127.0.0.1:11434").build();

        try (var response = post(path, request)) {
            assertRefused(response, BASE_URL_REFUSED);
        }
    }

    @Test
    @DisplayName("chat completions refuse a request whose substituted host is non-public")
    void chatCompletionRefusesSubstitutedNonPublicHost() {
        var providerName = UUID.randomUUID().toString();
        var model = "custom-llm/%s/127.0.0.1".formatted(providerName);
        client.createProviderApiKey(customProvider("http://{model}/v1").toBuilder()
                .providerName(providerName)
                .configuration(Map.of("provider_name", providerName, "models", model))
                .build(), apiKey, workspaceName, HttpStatus.SC_CREATED);
        var request = ChatCompletionRequest.builder().stream(false).model(model).addUserMessage("ping").build();

        try (var response = post("/v1/private/chat/completions", request)) {
            assertRefused(response, BASE_URL_REFUSED);
        }
    }

    private ProviderApiKey customProvider(String baseUrl) {
        return factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                .provider(LlmProvider.CUSTOM_LLM)
                .providerName(UUID.randomUUID().toString())
                .createdBy(USER)
                .lastUpdatedBy(USER)
                .baseUrl(baseUrl)
                .build();
    }

    /** A complete, valid recipe, so bean validation passes and only the destination can be refused. */
    private ProviderAuthConfig authConfig(String tokenUrl) {
        return ProviderAuthConfig.builder()
                .tokenUrl(tokenUrl)
                .sendAs(ProviderAuthConfig.SendAs.BASIC)
                .credentials(List.of(
                        credential("grant_type", "client_credentials", false),
                        credential("client_id", "opik", false),
                        credential("client_secret", UUID.randomUUID().toString(), true)))
                .tokenField("access_token")
                .expiresField("expires_in")
                .fallbackTtlSeconds(3600L)
                .build();
    }

    private ProviderAuthConfig.Credential credential(String key, String value, boolean secret) {
        return ProviderAuthConfig.Credential.builder().key(key).value(value).secret(secret).build();
    }

    private Response post(String path, Object body) {
        return clientSupport.target(TestUtils.getBaseUrl(clientSupport) + path)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(body));
    }

    private void assertRefused(Response response, ErrorMessage expected) {
        assertThat(response.getStatus()).isEqualTo(expected.getCode());
        assertThat(response.readEntity(ErrorMessage.class)).isEqualTo(expected);
    }

    private void assertStored(ProviderApiKey expected) {
        assertThat(client.getById(expected.id(), workspaceName, apiKey, HttpStatus.SC_OK))
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS)
                .isEqualTo(expected);
    }
}
