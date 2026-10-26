package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.EncryptedAuthConfig;
import com.comet.opik.api.LlmProvider;
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
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.LlmProviderApiKeyResourceClient;
import com.comet.opik.domain.LlmProviderApiKeyDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
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
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both destination guards in strict mode, as on Opik Cloud: POST and PATCH must refuse non-public provider
 * destinations at the endpoint and leave the stored row untouched. IP literals keep these cases free of DNS.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("LLM provider api key resource: strict destination guard")
@ExtendWith(DropwizardAppExtensionProvider.class)
class LlmProviderApiKeyResourceStrictDestinationTest {

    private static final String USER = UUID.randomUUID().toString();
    private static final String PUBLIC_BASE_URL = "http://1.1.1.1/v1";
    // spelled out rather than shared with the production code, so a changed message fails here
    private static final String REFUSED = "destination was refused, it resolves to a private or internal address, host '%s'";
    private static final String BASE_URL_HINT = " (self-hosted deployments with providers on internal networks can set LLM_PROVIDER_DESTINATION_GUARD=relaxed)";

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

    private TransactionTemplate mySqlTemplate;
    private LlmProviderApiKeyResourceClient client;

    private String apiKey;
    private String workspaceName;
    private String workspaceId;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, TransactionTemplate mySqlTemplate) {
        this.mySqlTemplate = mySqlTemplate;
        this.client = new LlmProviderApiKeyResourceClient(clientSupport);
        ClientSupportUtils.config(clientSupport);
    }

    @BeforeEach
    void setUp() {
        apiKey = UUID.randomUUID().toString();
        workspaceName = UUID.randomUUID().toString();
        workspaceId = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8081/tasks/log-level", "http://169.254.169.254/latest/meta-data",
            "http://10.0.0.1/v1"})
    @DisplayName("POST refuses a non-public base_url and stores nothing")
    void createRefusesNonPublicBaseUrl(String baseUrl) {
        try (var response = client.callCreateProviderApiKey(openAiProvider(baseUrl), apiKey, workspaceName)) {
            assertBaseUrlRefused(response, baseUrl);
        }

        // the listing can include the virtual free-model provider, so check for this destination only
        assertThat(client.getAll(workspaceName, apiKey).content())
                .extracting(ProviderApiKey::baseUrl)
                .doesNotContain(baseUrl);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:8081/tasks/log-level", "http://169.254.169.254/latest/meta-data",
            "http://10.0.0.1/v1"})
    @DisplayName("PATCH refuses a non-public base_url and keeps the stored one")
    void updateRefusesNonPublicBaseUrl(String baseUrl) {
        var created = client.createProviderApiKey(openAiProvider(PUBLIC_BASE_URL), apiKey, workspaceName,
                HttpStatus.SC_CREATED);
        // credentials resent, so the destination check is the only reason left to refuse
        var update = ProviderApiKeyUpdate.builder().baseUrl(baseUrl).apiKey(UUID.randomUUID().toString()).build();

        try (var response = client.callUpdateProviderApiKey(created.id(), update, apiKey, workspaceName)) {
            assertBaseUrlRefused(response, baseUrl);
        }

        assertThat(stored(created.id()).baseUrl()).isEqualTo(PUBLIC_BASE_URL);
    }

    @Test
    @DisplayName("POST and PATCH refuse a non-public auth_config token_url")
    void refusesNonPublicTokenUrl() {
        var internalTokenUrl = "https://127.0.0.1/oauth/token";
        var withInternalTokenUrl = customProvider().toBuilder()
                .apiKey(null)
                .authConfig(EncryptedAuthConfig.of(authConfig(internalTokenUrl)))
                .build();

        try (var response = client.callCreateProviderApiKey(withInternalTokenUrl, apiKey, workspaceName)) {
            assertTokenUrlRefused(response, internalTokenUrl);
        }

        var created = client.createProviderApiKey(customProvider(), apiKey, workspaceName, HttpStatus.SC_CREATED);
        var update = ProviderApiKeyUpdate.builder().apiKey("").authConfig(authConfig(internalTokenUrl)).build();

        try (var response = client.callUpdateProviderApiKey(created.id(), update, apiKey, workspaceName)) {
            assertTokenUrlRefused(response, internalTokenUrl);
        }

        assertThat(stored(created.id()).authConfig()).isNull();
    }

    @Test
    @DisplayName("a public base_url is still accepted in strict mode")
    void acceptsPublicBaseUrl() {
        var created = client.createProviderApiKey(openAiProvider(PUBLIC_BASE_URL), apiKey, workspaceName,
                HttpStatus.SC_CREATED);

        assertThat(stored(created.id()).baseUrl()).isEqualTo(PUBLIC_BASE_URL);
    }

    private ProviderApiKey openAiProvider(String baseUrl) {
        return factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                .provider(LlmProvider.OPEN_AI)
                .providerName(null)
                .baseUrl(baseUrl)
                .build();
    }

    private ProviderApiKey customProvider() {
        return factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                .provider(LlmProvider.CUSTOM_LLM)
                .providerName(UUID.randomUUID().toString())
                .baseUrl(PUBLIC_BASE_URL)
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

    private void assertBaseUrlRefused(Response response, String baseUrl) {
        assertRefused(response, REFUSED.formatted(URI.create(baseUrl).getHost()) + BASE_URL_HINT);
    }

    private void assertTokenUrlRefused(Response response, String tokenUrl) {
        assertRefused(response, REFUSED.formatted(URI.create(tokenUrl).getHost()));
    }

    private void assertRefused(Response response, String expectedMessage) {
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        assertThat(response.readEntity(ErrorMessage.class).getMessage()).isEqualTo(expectedMessage);
    }

    private ProviderApiKey stored(UUID id) {
        return mySqlTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(LlmProviderApiKeyDAO.class).findById(id, workspaceId));
    }
}
