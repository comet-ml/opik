package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ChatCompletionsClient;
import com.comet.opik.api.resources.utils.resources.LlmProviderApiKeyResourceClient;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.llm.antropic.AnthropicModelName;
import com.comet.opik.infrastructure.llm.gemini.GeminiModelName;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModelName;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Role;
import org.apache.http.HttpStatus;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.shaded.org.apache.commons.lang3.RandomStringUtils;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static com.comet.opik.domain.llm.ChatCompletionService.ERROR_EMPTY_MESSAGES;
import static com.comet.opik.domain.llm.ChatCompletionService.ERROR_NO_COMPLETION_TOKENS;
import static com.comet.opik.domain.llm.LlmProviderFactory.ERROR_MODEL_NOT_SUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/// For some providers, the tests need to make actual LLM calls. For that to work, the relevant API keys must be set in
/// the environment prior to running the tests. If an environment variable for a specific provider is not set, the
/// relevant test will be skipped for that provider.
/// - **Openai**: runs against a demo server and doesn't require an API key
/// - **Anthropic**: set `ANTHROPIC_API_KEY` to your anthropic api key
/// - **Gemini**: set `GEMINI_API_KEY` to your gemini api key
/// - **OpenRouter**: set `OPENROUTER_API_KEY` to your OpenRouter api key
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// Disabled because the tests require an API key to run and this seems to be failing in the CI pipeline
@ExtendWith(DropwizardAppExtensionProvider.class)
class ChatCompletionsResourceTest {

    private static final String API_KEY = RandomStringUtils.randomAlphanumeric(25);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = RandomStringUtils.randomAlphanumeric(20);
    private static final String USER = RandomStringUtils.randomAlphanumeric(20);

    private final RedisContainer REDIS_CONTAINER = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MY_SQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime WIRE_MOCK = WireMockUtils.startWireMock();

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS_CONTAINER, MY_SQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, ClickHouseContainerUtils.DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MY_SQL_CONTAINER.getJdbcUrl(),
                databaseAnalyticsFactory,
                WIRE_MOCK.runtimeInfo(),
                REDIS_CONTAINER.getRedisURI());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private ChatCompletionsClient chatCompletionsClient;
    private LlmProviderApiKeyResourceClient llmProviderApiKeyResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, Jdbi jdbi) throws SQLException {
        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(
                    connection,
                    MigrationUtils.CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        ClientSupportUtils.config(clientSupport);

        mockTargetWorkspace(WORKSPACE_NAME, WORKSPACE_ID);

        this.chatCompletionsClient = new ChatCompletionsClient(clientSupport);
        this.llmProviderApiKeyResourceClient = new LlmProviderApiKeyResourceClient(clientSupport);
    }

    private void mockTargetWorkspace(String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(WIRE_MOCK.server(), API_KEY, workspaceName, workspaceId, USER);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Create {
        @ParameterizedTest
        @MethodSource("testModelsProvider")
        @Disabled
        void create(
                String expectedModel, LlmProvider llmProvider, String llmProviderApiKey,
                BiConsumer<String, String> modelNameEvaluator) {
            assumeThat(llmProviderApiKey).isNotEmpty();

            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            createLlmProviderApiKey(workspaceName, llmProvider, llmProviderApiKey);

            var request = podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                    .stream(false)
                    .model(expectedModel)
                    .maxCompletionTokens(100)
                    .addUserMessage("Say 'Hello World'")
                    .build();

            var response = chatCompletionsClient.create(API_KEY, workspaceName, request);

            modelNameEvaluator.accept(response.model(), expectedModel);
            assertThat(response.choices()).anySatisfy(choice -> {
                assertThat(choice.message().content()).containsIgnoringCase("Hello World");
                assertThat(choice.message().role()).isEqualTo(Role.ASSISTANT);
            });
        }

        @ParameterizedTest
        @MethodSource("testModelsProvider")
        void createReturnsBadRequestWhenNoLlmProviderApiKey(String expectedModel, LlmProvider llmProvider) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);

            var request = podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                    .stream(false)
                    .model(expectedModel)
                    .addUserMessage("Say 'Hello World'")
                    .build();

            var errorMessage = chatCompletionsClient.create(API_KEY, workspaceName, request, HttpStatus.SC_BAD_REQUEST);

            assertThat(errorMessage.getCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(errorMessage.getMessage())
                    .containsIgnoringCase("API key not configured for LLM provider '%s'"
                            .formatted(llmProvider.getValue()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "non-existing-model"})
        void createReturnsBadRequestWhenModelIsInvalid(String model) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            createLlmProviderApiKey(workspaceName);

            var request = podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                    .stream(false)
                    .model(model)
                    .addUserMessage("Say 'Hello World'")
                    .build();

            var errorMessage = chatCompletionsClient.create(API_KEY, workspaceName, request, HttpStatus.SC_BAD_REQUEST);

            assertThat(errorMessage.getCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(errorMessage.getMessage())
                    .containsIgnoringCase(LlmProviderFactory.ERROR_MODEL_NOT_SUPPORTED.formatted(model));
        }

        @ParameterizedTest
        @MethodSource("testModelsProvider")
        @Disabled
        void createAndStreamResponse(
                String expectedModel, LlmProvider llmProvider, String llmProviderApiKey,
                BiConsumer<String, String> modelNameEvaluator) {
            assumeThat(llmProviderApiKey).isNotEmpty();

            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            createLlmProviderApiKey(workspaceName, llmProvider, llmProviderApiKey);

            var request = podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                    .stream(true)
                    .model(expectedModel)
                    .maxCompletionTokens(100)
                    .addUserMessage("Say 'Hello World'")
                    .build();

            var response = chatCompletionsClient.createAndStream(API_KEY, workspaceName, request);

            response.forEach(entity -> modelNameEvaluator.accept(entity.model(), expectedModel));

            var choices = response.stream().flatMap(entity -> entity.choices().stream()).toList();
            assertThat(choices)
                    .anySatisfy(choice -> assertThat(choice.delta().content())
                            .containsIgnoringCase("Hello"));
            assertThat(choices)
                    .anySatisfy(choice -> assertThat(choice.delta().content())
                            .containsIgnoringCase("World"));
            assertThat(choices).anySatisfy(choice -> assertThat(choice.delta().role())
                    .isEqualTo(Role.ASSISTANT));
        }

        private static Stream<Arguments> testModelsProvider() {
            BiConsumer<String, String> actualContainsExpectedEval = (actual, expected) -> assertThat(actual)
                    .containsIgnoringCase(expected);
            BiConsumer<String, String> expectedContainsActualEval = (actual, expected) -> assertThat(expected)
                    .containsIgnoringCase(actual);

            return Stream.of(
                    arguments(OpenaiModelName.GPT_4O_MINI.toString(), LlmProvider.OPEN_AI,
                            UUID.randomUUID().toString(), actualContainsExpectedEval),
                    arguments(AnthropicModelName.CLAUDE_3_5_SONNET_20240620.toString(), LlmProvider.ANTHROPIC,
                            System.getenv("ANTHROPIC_API_KEY"), actualContainsExpectedEval),
                    arguments(GeminiModelName.GEMINI_1_5_PRO.toString(), LlmProvider.GEMINI,
                            System.getenv("GEMINI_API_KEY"), actualContainsExpectedEval),
                    arguments(OpenRouterModelName.GOOGLE_GEMINI_2_0_FLASH_LITE_PREVIEW_02_05_FREE.toString(),
                            LlmProvider.OPEN_ROUTER, System.getenv("OPENROUTER_API_KEY"),
                            expectedContainsActualEval));
        }

        @Test
        void createAndStreamResponseGeminiInvalidApiKey() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            createLlmProviderApiKey(workspaceName, LlmProvider.GEMINI, "invalid-key");

            var request = podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                    .stream(true)
                    .model(GeminiModelName.GEMINI_1_5_PRO.toString())
                    .maxCompletionTokens(100)
                    .addUserMessage("Say 'Hello World'")
                    .build();

            var response = chatCompletionsClient.createAndGetStreamedError(API_KEY, workspaceName, request);

            assertThat(response).hasSize(1);
            assertThat(response.getFirst().getCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(response.getFirst().getMessage()).isEqualTo("API key not valid. Please pass a valid" +
                    " API key.");
            assertThat(response.getFirst().getDetails()).isEqualTo("INVALID_ARGUMENT");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "non-existing-model"})
        void createAndStreamResponseReturnsBadRequestWhenNoModel(String model) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            createLlmProviderApiKey(workspaceName);

            var request = podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                    .stream(true)
                    .model(model)
                    .addUserMessage("Say 'Hello World'")
                    .build();

            var errorMessage = chatCompletionsClient.createAndStreamError(API_KEY, workspaceName, request,
                    HttpStatus.SC_BAD_REQUEST);

            assertThat(errorMessage.getCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(errorMessage.getMessage())
                    .containsIgnoringCase(ERROR_MODEL_NOT_SUPPORTED.formatted(model));
        }
    }

    @ParameterizedTest
    @MethodSource
    void createAnthropicValidateMandatoryFields(ChatCompletionRequest request, String expectedErrorMessage) {
        String llmProviderApiKey = UUID.randomUUID().toString();

        var workspaceName = RandomStringUtils.randomAlphanumeric(20);
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(workspaceName, workspaceId);
        createLlmProviderApiKey(workspaceName, LlmProvider.ANTHROPIC, llmProviderApiKey);

        var errorMessage = chatCompletionsClient.create(API_KEY, workspaceName, request, HttpStatus.SC_BAD_REQUEST);

        assertThat(errorMessage.getCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        assertThat(errorMessage.getMessage())
                .containsIgnoringCase(expectedErrorMessage);
    }

    private Stream<Arguments> createAnthropicValidateMandatoryFields() {
        return Stream.of(
                arguments(named("no messages", podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                        .stream(false)
                        .model(AnthropicModelName.CLAUDE_3_5_SONNET_20240620.toString())
                        .maxCompletionTokens(100).build()),
                        ERROR_EMPTY_MESSAGES),
                arguments(named("no max tokens", podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                        .stream(false)
                        .model(AnthropicModelName.CLAUDE_3_5_SONNET_20240620.toString())
                        .addUserMessage("Say 'Hello World'").build()),
                        ERROR_NO_COMPLETION_TOKENS));
    }

    private void createLlmProviderApiKey(String workspaceName) {
        createLlmProviderApiKey(workspaceName, LlmProvider.OPEN_AI, UUID.randomUUID().toString());
    }

    private void createLlmProviderApiKey(String workspaceName, LlmProvider llmProvider, String llmProviderApiKey) {
        llmProviderApiKeyResourceClient.createProviderApiKey(
                llmProviderApiKey, llmProvider, API_KEY, workspaceName, 201);
    }
}
