package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.LlmProvider;
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
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.FreeModelConfig;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
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

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the Opik Built-in LLM provider functionality.
 * This test class runs with the built-in provider ENABLED to test virtual provider injection.
 *
 * Note: The case where the built-in provider is DISABLED is already covered by
 * {@link LlmProviderApiKeyResourceTest#createAndGetProviderApiKeyList()} which runs with the
 * default configuration (built-in provider disabled) and verifies the provider list is empty.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Built-in LLM Provider Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class LlmProviderApiKeyResourceBuiltinProviderTest {
    private static final String USER = UUID.randomUUID().toString();
    private static final String FREE_MODEL = "opik-free-model";
    private static final String ACTUAL_MODEL = "gpt-4o-mini";
    private static final String SPAN_PROVIDER = "openai";

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

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        // Create app with free model provider ENABLED
        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new TestDropwizardAppExtensionUtils.CustomConfig("freeModel.enabled", "true"),
                                new TestDropwizardAppExtensionUtils.CustomConfig("freeModel.actualModel",
                                        ACTUAL_MODEL),
                                new TestDropwizardAppExtensionUtils.CustomConfig("freeModel.spanProvider",
                                        SPAN_PROVIDER),
                                new TestDropwizardAppExtensionUtils.CustomConfig("freeModel.baseUrl",
                                        "https://test-endpoint.example.com"),
                                new TestDropwizardAppExtensionUtils.CustomConfig("freeModel.apiKey",
                                        "test-api-key")))
                        .build());
    }

    private LlmProviderApiKeyResourceClient llmProviderApiKeyResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) throws SQLException {
        this.llmProviderApiKeyResourceClient = new LlmProviderApiKeyResourceClient(client);
        ClientSupportUtils.config(client);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("Virtual built-in provider appears in provider list when enabled")
    void testFindProviders_includesVirtualBuiltinProvider_whenEnabled() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Get provider list - should include the virtual built-in provider
        var providerApiKeyPage = llmProviderApiKeyResourceClient.getAll(workspaceName, apiKey);

        // Assert the built-in provider is included
        assertThat(providerApiKeyPage.content()).hasSize(1);
        assertThat(providerApiKeyPage.size()).isEqualTo(1);
        assertThat(providerApiKeyPage.total()).isEqualTo(1);

        var builtinProvider = providerApiKeyPage.content().get(0);
        assertThat(builtinProvider.provider()).isEqualTo(LlmProvider.OPIK_FREE);
        assertThat(builtinProvider.id()).isEqualTo(FreeModelConfig.FREE_MODEL_PROVIDER_ID);
    }

    @Test
    @DisplayName("Virtual built-in provider has readOnly flag set to true")
    void testFindProviders_builtinProviderHasReadOnlyTrue() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var providerApiKeyPage = llmProviderApiKeyResourceClient.getAll(workspaceName, apiKey);

        var builtinProvider = providerApiKeyPage.content().stream()
                .filter(p -> p.provider() == LlmProvider.OPIK_FREE)
                .findFirst()
                .orElseThrow();

        assertThat(builtinProvider.readOnly()).isTrue();
    }

    @Test
    @DisplayName("Virtual built-in provider has correct configuration with model")
    void testFindProviders_builtinProviderHasCorrectConfiguration() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var providerApiKeyPage = llmProviderApiKeyResourceClient.getAll(workspaceName, apiKey);

        var builtinProvider = providerApiKeyPage.content().stream()
                .filter(p -> p.provider() == LlmProvider.OPIK_FREE)
                .findFirst()
                .orElseThrow();

        assertThat(builtinProvider.configuration()).isNotNull();
        assertThat(builtinProvider.configuration().get("models")).isEqualTo(FREE_MODEL);
    }

    @Test
    @DisplayName("Built-in provider cannot be deleted via batch delete")
    void testBatchDelete_ignoresBuiltinProvider() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Try to delete the free model provider
        llmProviderApiKeyResourceClient.batchDeleteProviderApiKey(
                Set.of(FreeModelConfig.FREE_MODEL_PROVIDER_ID), apiKey, workspaceName);

        // Verify the built-in provider still exists
        var providerApiKeyPage = llmProviderApiKeyResourceClient.getAll(workspaceName, apiKey);

        var builtinProvider = providerApiKeyPage.content().stream()
                .filter(p -> p.provider() == LlmProvider.OPIK_FREE)
                .findFirst();

        assertThat(builtinProvider).isPresent();
    }

    @Test
    @DisplayName("Virtual built-in provider is added at the end of the list (user providers first)")
    void testFindProviders_builtinProviderIsAddedAtEnd() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // First create a user provider
        var userProvider = ProviderApiKey.builder()
                .provider(LlmProvider.OPEN_AI)
                .apiKey("sk-test-key")
                .build();

        llmProviderApiKeyResourceClient.createProviderApiKey(userProvider, apiKey, workspaceName, 201);

        // Get provider list
        var providerApiKeyPage = llmProviderApiKeyResourceClient.getAll(workspaceName, apiKey);

        // Should have 2 providers
        assertThat(providerApiKeyPage.content()).hasSize(2);

        // User-configured provider should be first (index 0)
        assertThat(providerApiKeyPage.content().get(0).provider()).isEqualTo(LlmProvider.OPEN_AI);

        // Built-in provider should be last (index 1)
        assertThat(providerApiKeyPage.content().get(1).provider()).isEqualTo(LlmProvider.OPIK_FREE);
    }

    @Test
    @DisplayName("User-configured providers have readOnly flag set to false")
    void testFindProviders_userProvidersHaveReadOnlyFalse() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Create a user provider
        var userProvider = ProviderApiKey.builder()
                .provider(LlmProvider.ANTHROPIC)
                .apiKey("sk-ant-test-key")
                .build();

        llmProviderApiKeyResourceClient.createProviderApiKey(userProvider, apiKey, workspaceName, 201);

        // Get provider list
        var providerApiKeyPage = llmProviderApiKeyResourceClient.getAll(workspaceName, apiKey);

        var anthropicProvider = providerApiKeyPage.content().stream()
                .filter(p -> p.provider() == LlmProvider.ANTHROPIC)
                .findFirst()
                .orElseThrow();

        assertThat(anthropicProvider.readOnly()).isFalse();
    }
}
