package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.DatasetType;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.LlmProviderApiKeyResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ExperimentsResourceExecuteTest {

    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String CUSTOM_PROVIDER_NAME = "gateway";
    private static final String CUSTOM_MODEL = "custom-llm/" + CUSTOM_PROVIDER_NAME + "/mistral-large-2411";

    private static final String CHAT_COMPLETION = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-5",
             "choices":[{"index":0,"message":{"role":"assistant","content":"4"},"finish_reason":"stop"}]}
            """;

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);
        app = newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .build());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private ExperimentResourceClient experimentResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private LlmProviderApiKeyResourceClient llmProviderApiKeyResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        experimentResourceClient = new ExperimentResourceClient(client, baseURI, podamFactory);
        datasetResourceClient = new DatasetResourceClient(client, baseURI);
        llmProviderApiKeyResourceClient = new LlmProviderApiKeyResourceClient(client);
    }

    @Test
    void executeSendsTheSavedReasoningEffortForAnOpenAiModel() {
        var workspace = newWorkspace();
        var upstreamPath = stubUpstream();
        createProviderApiKey(workspace, ProviderApiKey.builder()
                .provider(LlmProvider.OPEN_AI)
                .apiKey("sk-test")
                .baseUrl(wireMock.runtimeInfo().getHttpBaseUrl() + upstreamPath)
                .build());

        execute(workspace, OpenaiModelName.GPT_5.toString(), "low");

        assertThat(awaitUpstreamRequestBodies(upstreamPath))
                .hasSize(1)
                .allSatisfy(body -> assertThat(body.path("reasoning_effort").asText()).isEqualTo("low"));
    }

    @Test
    void executeSendsNoReasoningEffortForANonOpenAiModel() {
        var workspace = newWorkspace();
        var upstreamPath = stubUpstream();
        createProviderApiKey(workspace, ProviderApiKey.builder()
                .provider(LlmProvider.CUSTOM_LLM)
                .providerName(CUSTOM_PROVIDER_NAME)
                .apiKey("gateway-key")
                .baseUrl(wireMock.runtimeInfo().getHttpBaseUrl() + upstreamPath)
                .configuration(Map.of("provider_name", CUSTOM_PROVIDER_NAME, "models", CUSTOM_MODEL))
                .build());

        execute(workspace, CUSTOM_MODEL, "low");

        assertThat(awaitUpstreamRequestBodies(upstreamPath))
                .hasSize(1)
                .allSatisfy(body -> assertThat(body.has("reasoning_effort")).isFalse());
    }

    @ParameterizedTest
    @EnumSource(DatasetType.class)
    void executeRejectsAnEmptyDatasetOrTestSuiteWithoutCreatingExperiments(DatasetType type) {
        var workspace = newWorkspace();
        var dataset = DatasetResourceClient.buildDataset(podamFactory).toBuilder().type(type).build();
        var datasetId = datasetResourceClient.createDataset(dataset, workspace.apiKey(), workspace.name());

        var request = ExperimentExecutionRequest.builder()
                .datasetName(dataset.name())
                .datasetId(datasetId)
                .prompts(List.of(ExperimentExecutionRequest.PromptVariant.builder()
                        .model(CUSTOM_MODEL)
                        .messages(List.of(ExperimentExecutionRequest.PromptVariant.Message.builder()
                                .role("user")
                                .content(TextNode.valueOf("{{question}}"))
                                .build()))
                        .build()))
                .build();

        try (var response = experimentResourceClient.callExecute(request, workspace.apiKey(), workspace.name())) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(response.readEntity(ErrorMessage.class).getMessage())
                    .isEqualTo("Dataset '%s' has no items. Add items to it before running an experiment"
                            .formatted(dataset.name()));
        }

        var experiments = experimentResourceClient.findExperiments(1, 10, datasetId, null, null, null, false, null,
                null, null, workspace.apiKey(), workspace.name(), HttpStatus.SC_OK);
        assertThat(experiments.content()).isEmpty();
    }

    private record Workspace(String apiKey, String name) {
    }

    private Workspace newWorkspace() {
        var workspace = new Workspace(
                "apiKey-" + UUID.randomUUID(),
                "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32));
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), workspace.apiKey(), workspace.name(),
                UUID.randomUUID().toString(), USER);
        return workspace;
    }

    private String stubUpstream() {
        var upstreamPath = "/upstream-" + RandomStringUtils.secure().nextAlphanumeric(12);
        wireMock.server().stubFor(post(urlPathEqualTo(upstreamPath + "/chat/completions"))
                .willReturn(okJson(CHAT_COMPLETION)));
        return upstreamPath;
    }

    private void createProviderApiKey(Workspace workspace, ProviderApiKey providerApiKey) {
        llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, workspace.apiKey(), workspace.name(),
                HttpStatus.SC_CREATED);
    }

    private void execute(Workspace workspace, String model, String reasoningEffort) {
        var dataset = DatasetResourceClient.buildDataset(podamFactory);
        var datasetId = datasetResourceClient.createDataset(dataset, workspace.apiKey(), workspace.name());
        datasetResourceClient.createDatasetItems(DatasetItemBatch.builder()
                .datasetId(datasetId)
                .items(List.of(DatasetItem.builder()
                        .source(DatasetItemSource.MANUAL)
                        .data(Map.of("question", TextNode.valueOf("What is 2 + 2?")))
                        .build()))
                .build(), workspace.name(), workspace.apiKey());

        var response = experimentResourceClient.execute(ExperimentExecutionRequest.builder()
                .datasetName(dataset.name())
                .datasetId(datasetId)
                .prompts(List.of(ExperimentExecutionRequest.PromptVariant.builder()
                        .model(model)
                        .messages(List.of(ExperimentExecutionRequest.PromptVariant.Message.builder()
                                .role("user")
                                .content(TextNode.valueOf("{{question}}"))
                                .build()))
                        .configs(Map.of("reasoningEffort", TextNode.valueOf(reasoningEffort)))
                        .build()))
                .build(), workspace.apiKey(), workspace.name());

        assertThat(response.totalItems()).isEqualTo(1);
    }

    private List<JsonNode> awaitUpstreamRequestBodies(String upstreamPath) {
        var pattern = postRequestedFor(urlPathEqualTo(upstreamPath + "/chat/completions"));
        await().atMost(Duration.ofSeconds(30))
                .until(() -> !wireMock.server().findAll(pattern).isEmpty());
        return wireMock.server().findAll(pattern).stream()
                .map(LoggedRequest::getBodyAsString)
                .map(JsonUtils::getJsonNodeFromString)
                .toList();
    }
}
