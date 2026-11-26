package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.OpenRouterRegionResponse;
import com.comet.opik.api.ProviderApiKey;
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
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterRegion;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Runs with the server's default OpenRouter URL on the EU host, as on a deployment that keeps OpenRouter traffic in
 * the EU through {@code LLM_PROVIDER_OPENROUTER_URL}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class LlmProviderApiKeyResourceOpenRouterRegionTest {

    private static final String USER = UUID.randomUUID().toString();
    private static final String SERVER_OPENROUTER_URL = "https://eu.openrouter.ai/api/v1";

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
                                new CustomConfig("llmProviderClient.openRouterUrl", SERVER_OPENROUTER_URL)))
                        .build());
    }

    private LlmProviderApiKeyResourceClient llmProviderApiKeyResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.llmProviderApiKeyResourceClient = new LlmProviderApiKeyResourceClient(client);
        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private record Workspace(String name, String apiKey) {
    }

    private Workspace newWorkspace() {
        var workspace = new Workspace(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), workspace.apiKey(), workspace.name(),
                UUID.randomUUID().toString(), USER);
        return workspace;
    }

    private void createOpenRouterKey(Workspace workspace, String baseUrl) {
        var providerApiKey = ProviderApiKey.builder()
                .provider(LlmProvider.OPEN_ROUTER)
                .apiKey(UUID.randomUUID().toString())
                .baseUrl(baseUrl)
                .build();
        llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, workspace.apiKey(), workspace.name(),
                201);
    }

    @Test
    void usesTheServerDefaultWhenTheWorkspaceHasNoOpenRouterKey() {
        var workspace = newWorkspace();

        try (var response = llmProviderApiKeyResourceClient.callGetOpenRouterRegion(workspace.apiKey(),
                workspace.name())) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(JsonUtils.getJsonNodeFromString(response.readEntity(String.class)))
                    .isEqualTo(JsonUtils.getJsonNodeFromString("""
                            {"region": "eu"}"""));
        }
    }

    @Test
    void usesTheServerDefaultWhenTheOpenRouterKeyHasNoBaseUrl() {
        var workspace = newWorkspace();
        createOpenRouterKey(workspace, null);

        var actual = llmProviderApiKeyResourceClient.getOpenRouterRegion(workspace.apiKey(), workspace.name());

        assertThat(actual).isEqualTo(OpenRouterRegionResponse.builder().region(OpenRouterRegion.EU).build());
    }

    static Stream<Arguments> workspaceBaseUrls() {
        return Stream.of(
                arguments("https://openrouter.ai/api/v1", OpenRouterRegion.GLOBAL),
                arguments("https://us.openrouter.ai/api/v1", OpenRouterRegion.US),
                arguments("https://llm-proxy.example.com/openrouter/api/v1", OpenRouterRegion.UNKNOWN));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("workspaceBaseUrls")
    void usesTheOpenRouterKeysBaseUrlOverTheServerDefault(String baseUrl, OpenRouterRegion expected) {
        var workspace = newWorkspace();
        createOpenRouterKey(workspace, baseUrl);

        var actual = llmProviderApiKeyResourceClient.getOpenRouterRegion(workspace.apiKey(), workspace.name());

        assertThat(actual).isEqualTo(OpenRouterRegionResponse.builder().region(expected).build());
    }

    @Test
    void ignoresAnotherWorkspacesOpenRouterKey() {
        createOpenRouterKey(newWorkspace(), "https://us.openrouter.ai/api/v1");
        var workspace = newWorkspace();

        var actual = llmProviderApiKeyResourceClient.getOpenRouterRegion(workspace.apiKey(), workspace.name());

        assertThat(actual).isEqualTo(OpenRouterRegionResponse.builder().region(OpenRouterRegion.EU).build());
    }
}
