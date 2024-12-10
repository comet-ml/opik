package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.ProviderApiKeyDAO;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.EncryptionService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Proxy Resource Test")
class ProxyResourceTest {
    public static final String URL_TEMPLATE = "%s/v1/private/proxy";

    public static final String[] IGNORED_FIELDS = {"createdBy", "lastUpdatedBy", "createdAt", "lastUpdatedAt"};

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private static final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();
    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    private static final String USER = UUID.randomUUID().toString();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;

    static {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private EncryptionService encryptionService;
    private TransactionTemplate mySqlTemplate;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi, EncryptionService encryptionService,
            TransactionTemplate mySqlTemplate) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;
        this.encryptionService = encryptionService;
        this.mySqlTemplate = mySqlTemplate;

        ClientSupportUtils.config(client);
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("Create and update provider Api Key")
    void createAndUpdateProviderApiKey() {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String provider = factory.manufacturePojo(String.class);
        String providerApiKey = factory.manufacturePojo(String.class);

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var id = createProviderApiKey(provider, providerApiKey, apiKey, workspaceName, 201);
        var expectedProviderApiKey = ProviderApiKey.builder().id(id).provider(provider).build();
        getAndAssertProviderApiKey(expectedProviderApiKey, apiKey, workspaceName);
        checkEncryption(id, workspaceId, providerApiKey);

        String newProviderApiKey = factory.manufacturePojo(String.class);
        updateProviderApiKey(id, newProviderApiKey, apiKey, workspaceName, 204);
        checkEncryption(id, workspaceId, newProviderApiKey);
    }

    @Test
    @DisplayName("Create provider Api Key for existing provider should fail")
    void createProviderApiKeyForExistingProviderShouldFail() {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String provider = factory.manufacturePojo(String.class);
        String providerApiKey = factory.manufacturePojo(String.class);

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        createProviderApiKey(provider, providerApiKey, apiKey, workspaceName, 201);
        createProviderApiKey(provider, providerApiKey, apiKey, workspaceName, 409);
    }

    @Test
    @DisplayName("Update provider Api Key for non-existing Id")
    void updateProviderFail() {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String providerApiKey = factory.manufacturePojo(String.class);

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // for non-existing id
        updateProviderApiKey(UUID.randomUUID(), providerApiKey, apiKey, workspaceName, 404);
    }

    private UUID createProviderApiKey(String provider, String providerApiKey, String apiKey, String workspaceName,
            int expectedStatus) {
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .path("api_key")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(ProviderApiKey.builder().provider(provider).apiKey(providerApiKey).build()))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            if (expectedStatus == 201) {
                return TestUtils.getIdFromLocation(actualResponse.getLocation());
            }

            return null;
        }
    }

    private void updateProviderApiKey(UUID id, String providerApiKey, String apiKey, String workspaceName,
            int expectedStatus) {
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .path("api_key/" + id.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(ProviderApiKeyUpdate.builder().apiKey(providerApiKey).build()))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }

    private void getAndAssertProviderApiKey(ProviderApiKey expected, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .path("api_key/" + expected.id().toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualResponse.hasEntity()).isTrue();

            var actualEntity = actualResponse.readEntity(ProviderApiKey.class);
            assertThat(actualEntity.provider()).isEqualTo(expected.provider());
            assertThat(actualEntity.apiKey()).isBlank();
        }
    }

    private void checkEncryption(UUID id, String workspaceId, String expectedApiKey) {
        String actualEncryptedApiKey = mySqlTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(ProviderApiKeyDAO.class);
            return repository.findById(id, workspaceId).apiKey();
        });
        assertThat(encryptionService.decrypt(actualEncryptedApiKey)).isEqualTo(expectedApiKey);
    }
}