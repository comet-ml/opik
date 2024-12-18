package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Page;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.LlmProviderApiKeyResourceClient;
import com.comet.opik.domain.LlmProviderApiKeyDAO;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Proxy Resource Test")
class LlmProviderApiKeyResourceTest {
    public static final String URL_TEMPLATE = "%s/v1/private/llm-provider-key";

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

    private TransactionTemplate mySqlTemplate;
    private LlmProviderApiKeyResourceClient llmProviderApiKeyResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi,
            TransactionTemplate mySqlTemplate) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.mySqlTemplate = mySqlTemplate;
        this.llmProviderApiKeyResourceClient = new LlmProviderApiKeyResourceClient(client);

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
        String providerApiKey = factory.manufacturePojo(String.class);

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var expectedProviderApiKey = llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey,
                workspaceName, 201);
        getAndAssertProviderApiKey(expectedProviderApiKey, apiKey, workspaceName);
        checkEncryption(expectedProviderApiKey.id(), workspaceId, providerApiKey);

        String newProviderApiKey = factory.manufacturePojo(String.class);
        llmProviderApiKeyResourceClient.updateProviderApiKey(expectedProviderApiKey.id(), newProviderApiKey, apiKey,
                workspaceName, 204);
        checkEncryption(expectedProviderApiKey.id(), workspaceId, newProviderApiKey);
    }

    @Test
    @DisplayName("Create and batch delete provider Api Keys")
    void createAndBatchDeleteProviderApiKeys() {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String providerApiKey = factory.manufacturePojo(String.class);

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var createdProviderApiKey = llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey,
                workspaceName, 201);

        // Delete
        llmProviderApiKeyResourceClient.batchDeleteProviderApiKey(Set.of(createdProviderApiKey.id()), apiKey,
                workspaceName);

        // Delete one more time for non existing key, should return same 204 response
        llmProviderApiKeyResourceClient.batchDeleteProviderApiKey(Set.of(createdProviderApiKey.id()), apiKey,
                workspaceName);

        // Check that it was deleted
        llmProviderApiKeyResourceClient.getById(createdProviderApiKey.id(), workspaceName, apiKey, 404);
    }

    @Test
    @DisplayName("Create provider Api Key for existing provider should fail")
    void createProviderApiKeyForExistingProviderShouldFail() {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String providerApiKey = factory.manufacturePojo(String.class);

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey, workspaceName, 201);
        llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey, workspaceName, 409);
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
        llmProviderApiKeyResourceClient.updateProviderApiKey(UUID.randomUUID(), providerApiKey, apiKey, workspaceName,
                404);
    }

    @Test
    @DisplayName("Create and get provider Api Keys List")
    void createAndGetProviderApiKeyList() {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String providerApiKey = factory.manufacturePojo(String.class);

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // No LLM Provider api keys, expect empty response
        var actualProviderApiKeyPage = llmProviderApiKeyResourceClient.getAll(workspaceName, apiKey);
        assertPage(actualProviderApiKeyPage, List.of());

        // Create LLM Provider api key
        var expectedProviderApiKey = llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, apiKey,
                workspaceName, 201);
        actualProviderApiKeyPage = llmProviderApiKeyResourceClient.getAll(workspaceName, apiKey);
        assertPage(actualProviderApiKeyPage, List.of(expectedProviderApiKey));

    }

    private void getAndAssertProviderApiKey(ProviderApiKey expected, String apiKey, String workspaceName) {
        var actualEntity = llmProviderApiKeyResourceClient.getById(expected.id(), workspaceName, apiKey, 200);
        assertThat(actualEntity.provider()).isEqualTo(expected.provider());
        assertThat(actualEntity.apiKey()).isBlank();
    }

    private void checkEncryption(UUID id, String workspaceId, String expectedApiKey) {
        String actualEncryptedApiKey = mySqlTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(LlmProviderApiKeyDAO.class);
            return repository.findById(id, workspaceId).apiKey();
        });
        assertThat(EncryptionUtils.decrypt(actualEncryptedApiKey)).isEqualTo(expectedApiKey);
    }

    private void assertPage(Page<ProviderApiKey> actual, List<ProviderApiKey> expected) {
        assertThat(actual.content()).hasSize(expected.size());
        assertThat(actual.page()).isEqualTo(0);
        assertThat(actual.total()).isEqualTo(expected.size());
        assertThat(actual.size()).isEqualTo(expected.size());

        assertThat(actual.content().stream().map(ProviderApiKey::provider).toList())
                .isEqualTo(expected.stream().map(ProviderApiKey::provider).toList());
    }
}
