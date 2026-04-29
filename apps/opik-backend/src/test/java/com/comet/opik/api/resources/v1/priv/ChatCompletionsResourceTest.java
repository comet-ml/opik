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
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ChatCompletionsClient;
import com.comet.opik.api.resources.utils.resources.LlmProviderApiKeyResourceClient;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.llm.antropic.AnthropicModelName;
import com.comet.opik.infrastructure.llm.gemini.GeminiModelName;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModelName;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Content;
import dev.langchain4j.model.openai.internal.chat.ContentType;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.Role;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
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
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static com.comet.opik.domain.llm.ChatCompletionService.ERROR_EMPTY_MESSAGES;
import static com.comet.opik.domain.llm.ChatCompletionService.ERROR_NO_COMPLETION_TOKENS;
import static com.comet.opik.domain.llm.LlmProviderFactory.ERROR_MODEL_NOT_SUPPORTED;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
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
    private final MySQLContainer MY_SQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
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

        MigrationUtils.runMysqlDbMigration(MY_SQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MY_SQL_CONTAINER.getJdbcUrl(),
                databaseAnalyticsFactory,
                WIRE_MOCK.runtimeInfo(),
                REDIS_CONTAINER.getRedisURI());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private ChatCompletionsClient chatCompletionsClient;
    private LlmProviderApiKeyResourceClient llmProviderApiKeyResourceClient;
    private ClientSupport clientSupport;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport) {

        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(WORKSPACE_NAME, WORKSPACE_ID);

        this.chatCompletionsClient = new ChatCompletionsClient(clientSupport);
        this.llmProviderApiKeyResourceClient = new LlmProviderApiKeyResourceClient(clientSupport);
        this.clientSupport = clientSupport;
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
                    .containsIgnoringCase("API key not configured for LLM. provider='%s', model='%s'"
                            .formatted(llmProvider.getValue(), expectedModel));
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
                    arguments(AnthropicModelName.CLAUDE_SONNET_3_7.toString(), LlmProvider.ANTHROPIC,
                            System.getenv("ANTHROPIC_API_KEY"), actualContainsExpectedEval),
                    arguments(GeminiModelName.GEMINI_2_0_FLASH.toString(), LlmProvider.GEMINI,
                            System.getenv("GEMINI_API_KEY"), actualContainsExpectedEval),
                    arguments(OpenRouterModelName.GOOGLE_GEMINI_2_5_FLASH_LITE_PREVIEW_09_2025.toString(),
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
                    .model(GeminiModelName.GEMINI_2_0_FLASH.toString())
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
                        .model(AnthropicModelName.CLAUDE_SONNET_3_7.toString())
                        .maxCompletionTokens(100).build()),
                        ERROR_EMPTY_MESSAGES),
                arguments(named("no max tokens", podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                        .stream(false)
                        .model(AnthropicModelName.CLAUDE_SONNET_3_7.toString())
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

    /// Validates that 4xx errors from the upstream LLM provider gateway reach the API caller
    /// intact. Covers both the OpenAI-compatible error body shape (which current
    /// [com.comet.opik.infrastructure.llm.customllm.CustomLlmErrorMessage] expects) and the
    /// Azure OpenAI error body shape (nested error object). Relevant to OPIK-4551 AC #9.
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Custom LLM provider — upstream gateway error propagation")
    class CustomLlmErrorPropagation {

        private static final String CUSTOM_PROVIDER_NAME = "azure-gw";
        private static final String DEPLOYMENT = "gpt-4o";
        private static final String MODEL = "custom-llm/" + CUSTOM_PROVIDER_NAME + "/" + DEPLOYMENT;
        private static final String CHAT_COMPLETIONS_REGEX = ".*/chat/completions.*";
        private static final String CHAT_COMPLETIONS_PATH = "/v1/private/chat/completions";

        @BeforeEach
        void resetUpstreamStubs() {
            // WireMock matches most-recently-registered first; reset between tests so
            // stubs from one case don't intercept another. Each test re-registers its
            // own workspace auth stub via mockTargetWorkspace().
            WIRE_MOCK.server().resetAll();
        }

        @Test
        @DisplayName("OpenAI-style {error:string} body — message propagates, status collapses to 400")
        void openAiStyleErrorBodyIsPropagated() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            createCustomProvider(workspaceName);

            var upstreamBody = "{\"error\": \"Invalid API key provided\"}";
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_UNAUTHORIZED)
                            .withHeader("Content-Type", "application/json")
                            .withBody(upstreamBody)));

            var request = ChatCompletionRequest.builder()
                    .stream(false)
                    .model(MODEL)
                    .addUserMessage("ping")
                    .build();

            var raw = postChatCompletion(workspaceName, request);
            var status = raw.getStatus();
            var body = raw.readEntity(String.class);

            // CustomLlmErrorMessage.toErrorMessage() hardcodes 400 regardless of upstream
            // status. Message text does propagate cleanly.
            assertThat(status).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(body).contains("Invalid API key provided");
        }

        @Test
        @DisplayName("Azure-like URL path suffix in baseUrl is preserved — /openai/deployments/{dep}/chat/completions")
        void baseUrlPathSuffixIsPreserved() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);

            // Mimic an Azure APIM gateway URL shape: /openai/deployments/{deployment}
            var azurePathSuffix = "/openai/deployments/" + DEPLOYMENT;
            var baseUrl = WIRE_MOCK.runtimeInfo().getHttpBaseUrl() + azurePathSuffix;
            createCustomProvider(workspaceName, baseUrl);

            // Narrow the stub to the Azure path prefix so it doesn't shadow the workspace
            // auth stub that Opik's backend hits on WireMock for every incoming request.
            WIRE_MOCK.server().stubFor(post(urlPathMatching("/openai/deployments/.*"))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody(SUCCESS_BODY)));

            var request = ChatCompletionRequest.builder()
                    .stream(false)
                    .model(MODEL)
                    .addUserMessage("ping")
                    .build();

            var raw = postChatCompletion(workspaceName, request);
            var status = raw.getStatus();
            var body = raw.readEntity(String.class);

            // AC #1, #6, #8: deployment stays in the URL path, not inferred from model.
            WIRE_MOCK.server().verify(postRequestedFor(
                    urlPathMatching(azurePathSuffix + "/chat/completions")));
            assertThat(status).isEqualTo(HttpStatus.SC_OK);
        }

        @Test
        @DisplayName("Query param in baseUrl is mangled — proves we need url_query_params config")
        void queryParamInBaseUrlIsMangled() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);

            // User tries to smuggle Azure's mandatory `api-version` query param via baseUrl.
            var azurePathSuffix = "/openai/deployments/" + DEPLOYMENT;
            var apiVersion = "2024-08-01-preview";
            var baseUrl = WIRE_MOCK.runtimeInfo().getHttpBaseUrl()
                    + azurePathSuffix + "?api-version=" + apiVersion;
            createCustomProvider(workspaceName, baseUrl);

            // Narrow the stub to the Azure path prefix so it doesn't shadow the workspace
            // auth stub that Opik's backend hits on WireMock for every incoming request.
            WIRE_MOCK.server().stubFor(post(urlPathMatching("/openai/deployments/.*"))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody(SUCCESS_BODY)));

            var request = ChatCompletionRequest.builder()
                    .stream(false)
                    .model(MODEL)
                    .addUserMessage("ping")
                    .build();

            var raw = postChatCompletion(workspaceName, request);
            var status = raw.getStatus();
            var body = raw.readEntity(String.class);

            // AC #4: api-version must be a real query param. If we put it in baseUrl,
            // LC4j's HttpRequest.Builder.url(baseUrl, path) string-concatenates with "/",
            // producing  .../openai/deployments/gpt-4o?api-version=X/chat/completions  —
            // the HTTP client either rejects the URL or sends it to the wrong path with a
            // corrupted query string. Either way, the upstream never sees a clean
            // `chat/completions` request with `api-version=X`. Assert this is the case.
            var requestsOnChatCompletions = WIRE_MOCK.server().findAll(postRequestedFor(
                    urlPathMatching(".*/chat/completions")));
            var requestsWithCleanApiVersion = WIRE_MOCK.server().findAll(postRequestedFor(
                    urlPathMatching(azurePathSuffix + "/chat/completions"))
                    .withQueryParam("api-version",
                            com.github.tomakehurst.wiremock.client.WireMock.equalTo(apiVersion)));

            // At most one of these is true; neither should be. The test asserts that the
            // "smuggle via baseUrl" pattern does NOT produce a correct request — which is
            // exactly why OPIK-4551 needs a dedicated url_query_params config key.
            assertThat(requestsWithCleanApiVersion)
                    .as("baseUrl-embedded query param should not produce a clean request with ?api-version=X at /chat/completions")
                    .isEmpty();
        }

        @Test
        @DisplayName("Azure-style {error:{code,message}} body — surfaces as clean 4xx with extracted message")
        void azureStyleErrorBodyIsPropagated() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            createCustomProvider(workspaceName);

            // Real Azure OpenAI error payload shape — nested object, not a string.
            var upstreamBody = """
                    {"error": {"code": "InvalidAPIVersion", \
                    "message": "The API version 2024-08-01-preview is not supported by this service."}}""";
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_BAD_REQUEST)
                            .withHeader("Content-Type", "application/json")
                            .withBody(upstreamBody)));

            var request = ChatCompletionRequest.builder()
                    .stream(false)
                    .model(MODEL)
                    .addUserMessage("ping")
                    .build();

            var raw = postChatCompletion(workspaceName, request);
            var status = raw.getStatus();
            var body = raw.readEntity(String.class);

            // CustomLlmErrorMessage now understands both shapes: the historical
            // {"error": "<string>"} form and Azure's nested {"error": {"code", "message"}}
            // form. The gateway's human-readable message surfaces in the response
            // message field. HTTP status is 400 because `InvalidAPIVersion` isn't in
            // the OpenAI-compat code table, and CustomLlmErrorMessage defaults unknown
            // codes to 400 (gateway errors are client errors). The code itself lives in
            // ErrorMessage.details, which Dropwizard's default mapper drops when
            // re-serializing via ClientErrorException; visible-through-API checks must
            // focus on the message.
            assertThat(status).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(body).contains("The API version 2024-08-01-preview is not supported");
            // The raw embedded JSON wrapper the old 500 path used is no longer present.
            assertThat(body).doesNotContain("Unexpected error calling LLM provider");
        }

        @Test
        @DisplayName("{model} placeholder in base URL is substituted with the stripped model name at request time")
        void modelPlaceholderIsSubstitutedInUrl() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);

            var firstDeployment = "gpt-4o-mini-ZA";
            var secondDeployment = "gpt-4o-ZA";
            var firstModel = "custom-llm/" + CUSTOM_PROVIDER_NAME + "/" + firstDeployment;
            var secondModel = "custom-llm/" + CUSTOM_PROVIDER_NAME + "/" + secondDeployment;
            var baseUrl = WIRE_MOCK.runtimeInfo().getHttpBaseUrl() + "/openai/deployments/{model}";

            var providerApiKey = ProviderApiKey.builder()
                    .provider(LlmProvider.CUSTOM_LLM)
                    .providerName(CUSTOM_PROVIDER_NAME)
                    .apiKey("dummy-key")
                    .baseUrl(baseUrl)
                    .configuration(Map.of(
                            "provider_name", CUSTOM_PROVIDER_NAME,
                            "models", firstModel + "," + secondModel))
                    .build();
            llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, API_KEY, workspaceName,
                    HttpStatus.SC_CREATED);

            WIRE_MOCK.server().stubFor(post(urlPathMatching("/openai/deployments/.*/chat/completions"))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody(SUCCESS_BODY)));

            // Request 1 — first deployment
            var firstRequest = ChatCompletionRequest.builder()
                    .stream(false)
                    .model(firstModel)
                    .addUserMessage("ping-1")
                    .build();
            assertThat(postChatCompletion(workspaceName, firstRequest).getStatus())
                    .isEqualTo(HttpStatus.SC_OK);

            // Request 2 — second deployment, same provider entry
            var secondRequest = ChatCompletionRequest.builder()
                    .stream(false)
                    .model(secondModel)
                    .addUserMessage("ping-2")
                    .build();
            assertThat(postChatCompletion(workspaceName, secondRequest).getStatus())
                    .isEqualTo(HttpStatus.SC_OK);

            WIRE_MOCK.server().verify(postRequestedFor(
                    urlPathMatching("/openai/deployments/" + firstDeployment + "/chat/completions")));
            WIRE_MOCK.server().verify(postRequestedFor(
                    urlPathMatching("/openai/deployments/" + secondDeployment + "/chat/completions")));
            // Literal placeholder must not leak through.
            assertThat(WIRE_MOCK.server().findAll(postRequestedFor(
                    urlPathMatching(".*%7Bmodel%7D.*")))).isEmpty();
            assertThat(WIRE_MOCK.server().findAll(postRequestedFor(
                    urlPathMatching(".*\\{model\\}.*")))).isEmpty();
        }

        @Test
        @DisplayName("auth_header_name adds a custom header alongside the default Authorization: Bearer")
        void authHeaderNameAddsCustomHeaderAlongsideBearer() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);

            var providerApiKey = ProviderApiKey.builder()
                    .provider(LlmProvider.CUSTOM_LLM)
                    .providerName(CUSTOM_PROVIDER_NAME)
                    .apiKey("azure-key-sentinel")
                    .baseUrl(WIRE_MOCK.runtimeInfo().getHttpBaseUrl())
                    .configuration(Map.of(
                            "provider_name", CUSTOM_PROVIDER_NAME,
                            "models", MODEL,
                            "auth_header_name", "api-key"))
                    .build();
            llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, API_KEY, workspaceName,
                    HttpStatus.SC_CREATED);

            WIRE_MOCK.server().stubFor(post(urlPathMatching(".*/chat/completions.*"))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody(SUCCESS_BODY)));

            var request = ChatCompletionRequest.builder()
                    .stream(false)
                    .model(MODEL)
                    .addUserMessage("ping")
                    .build();

            assertThat(postChatCompletion(workspaceName, request).getStatus()).isEqualTo(HttpStatus.SC_OK);

            WIRE_MOCK.server().verify(postRequestedFor(urlPathMatching(".*/chat/completions.*"))
                    .withHeader("Authorization", equalTo("Bearer azure-key-sentinel"))
                    .withHeader("api-key", equalTo("azure-key-sentinel")));
        }

        @Test
        @DisplayName("suppress_default_auth drops Authorization and sends only the custom header")
        void suppressDefaultAuthDropsBearer() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);

            var providerApiKey = ProviderApiKey.builder()
                    .provider(LlmProvider.CUSTOM_LLM)
                    .providerName(CUSTOM_PROVIDER_NAME)
                    .apiKey("strict-apim-key")
                    .baseUrl(WIRE_MOCK.runtimeInfo().getHttpBaseUrl())
                    .configuration(Map.of(
                            "provider_name", CUSTOM_PROVIDER_NAME,
                            "models", MODEL,
                            "auth_header_name", "api-key",
                            "suppress_default_auth", "true"))
                    .build();
            llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, API_KEY, workspaceName,
                    HttpStatus.SC_CREATED);

            WIRE_MOCK.server().stubFor(post(urlPathMatching(".*/chat/completions.*"))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody(SUCCESS_BODY)));

            var request = ChatCompletionRequest.builder()
                    .stream(false)
                    .model(MODEL)
                    .addUserMessage("ping")
                    .build();

            assertThat(postChatCompletion(workspaceName, request).getStatus()).isEqualTo(HttpStatus.SC_OK);

            WIRE_MOCK.server().verify(postRequestedFor(urlPathMatching(".*/chat/completions.*"))
                    .withHeader("api-key", equalTo("strict-apim-key"))
                    .withoutHeader("Authorization"));
        }

        @Test
        @DisplayName("url_query_params configuration is appended to upstream request URL")
        void urlQueryParamsAreAppendedToUpstreamRequest() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);

            var providerApiKey = ProviderApiKey.builder()
                    .provider(LlmProvider.CUSTOM_LLM)
                    .providerName(CUSTOM_PROVIDER_NAME)
                    .apiKey("dummy-key")
                    .baseUrl(WIRE_MOCK.runtimeInfo().getHttpBaseUrl())
                    .configuration(Map.of(
                            "provider_name", CUSTOM_PROVIDER_NAME,
                            "models", MODEL,
                            "url_query_params",
                            "{\"api-version\":\"2024-08-01-preview\",\"other\":\"value\"}"))
                    .build();
            llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, API_KEY, workspaceName,
                    HttpStatus.SC_CREATED);

            WIRE_MOCK.server().stubFor(post(urlPathMatching(".*/chat/completions.*"))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody(SUCCESS_BODY)));

            var request = ChatCompletionRequest.builder()
                    .stream(false)
                    .model(MODEL)
                    .addUserMessage("ping")
                    .build();

            var raw = postChatCompletion(workspaceName, request);
            assertThat(raw.getStatus()).isEqualTo(HttpStatus.SC_OK);

            var received = WIRE_MOCK.server().findAll(postRequestedFor(
                    urlPathMatching(".*/chat/completions.*"))).getFirst();

            WIRE_MOCK.server().verify(postRequestedFor(urlPathMatching(".*/chat/completions.*"))
                    .withQueryParam("api-version", equalTo("2024-08-01-preview"))
                    .withQueryParam("other", equalTo("value")));
        }

        @Test
        @DisplayName("Backward-compat lock — legacy Custom LLM config behaves identically to today")
        void legacyCustomProviderBehavesAsTodayWhenNoNewKeysSet() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);

            // Minimal, pre-OPIK-4551 config: api key + base URL + models. No new configuration
            // keys (url_query_params, auth_header_name, suppress_default_auth), no {model}
            // placeholder, no custom headers. Fails the moment any later change mutates the
            // default path.
            var providerApiKey = ProviderApiKey.builder()
                    .provider(LlmProvider.CUSTOM_LLM)
                    .providerName(CUSTOM_PROVIDER_NAME)
                    .apiKey("legacy-key-sentinel")
                    .baseUrl(WIRE_MOCK.runtimeInfo().getHttpBaseUrl())
                    .configuration(Map.of(
                            "provider_name", CUSTOM_PROVIDER_NAME,
                            "models", MODEL))
                    .build();
            llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, API_KEY, workspaceName,
                    HttpStatus.SC_CREATED);

            WIRE_MOCK.server().stubFor(post(urlPathMatching(".*/chat/completions"))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody(SUCCESS_BODY)));

            var request = ChatCompletionRequest.builder()
                    .stream(false)
                    .model(MODEL)
                    .addUserMessage("ping")
                    .build();

            var raw = postChatCompletion(workspaceName, request);
            var status = raw.getStatus();
            var received = WIRE_MOCK.server().findAll(postRequestedFor(
                    urlPathMatching(".*/chat/completions"))).getFirst();

            // Contract 1: URL is exactly base_url + "/chat/completions". No query params added.
            assertThat(received.getUrl())
                    .as("URL should not be mutated when no new config keys are set")
                    .isEqualTo("/chat/completions")
                    .doesNotContain("?");
            // Contract 2: default Authorization: Bearer header is still sent.
            assertThat(received.getHeader("Authorization"))
                    .as("Authorization: Bearer should remain the default when suppress_default_auth is unset")
                    .isEqualTo("Bearer legacy-key-sentinel");
            // Contract 3: caller sees a 200 with the upstream body unchanged.
            assertThat(status).isEqualTo(HttpStatus.SC_OK);
        }

        @Test
        @DisplayName("AC #5 — vision-capable model (gpt-4o) preserves structured content array")
        void structuredContentIsPreservedForVisionModels() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            // Azure-shaped baseUrl so the outgoing request lands on our stub.
            var baseUrl = WIRE_MOCK.runtimeInfo().getHttpBaseUrl()
                    + "/openai/deployments/" + DEPLOYMENT;
            createCustomProvider(workspaceName, baseUrl);

            WIRE_MOCK.server().stubFor(post(urlPathMatching("/openai/deployments/.*"))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody(SUCCESS_BODY)));

            var request = structuredContentRequest(MODEL);
            var raw = postChatCompletion(workspaceName, request);
            var status = raw.getStatus();
            var responseBody = raw.readEntity(String.class);

            var upstreamBody = upstreamBodyOrFail(status, responseBody);

            // gpt-4o hits ModelCapabilities.supportsVision() == true, so the normalizer
            // takes the expandImagePlaceholders path and preserves structured content.
            // AC #5 holds for vision-capable deployments fronting OpenAI-compat gateways.
            assertThat(status).isEqualTo(HttpStatus.SC_OK);
            assertThat(upstreamBody)
                    .contains("\"type\" : \"text\"")
                    .contains("\"text\" : \"Knock knock.\"");
        }

        @Test
        @DisplayName("AC #5 gap — non-vision model flattens structured content to string")
        void structuredContentIsFlattenedForNonVisionModels() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            // Use a model name that ModelCapabilities does NOT recognize as vision-capable.
            // This mirrors a customer whose deployment name doesn't match a known pattern.
            var nonVisionModel = "custom-llm/" + CUSTOM_PROVIDER_NAME + "/not-a-vision-model-xyz";
            var baseUrl = WIRE_MOCK.runtimeInfo().getHttpBaseUrl()
                    + "/openai/deployments/not-a-vision-model-xyz";

            var providerApiKey = ProviderApiKey.builder()
                    .provider(LlmProvider.CUSTOM_LLM)
                    .providerName(CUSTOM_PROVIDER_NAME)
                    .apiKey("dummy-key")
                    .baseUrl(baseUrl)
                    .configuration(Map.of(
                            "provider_name", CUSTOM_PROVIDER_NAME,
                            "models", nonVisionModel))
                    .build();
            llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, API_KEY, workspaceName,
                    HttpStatus.SC_CREATED);

            WIRE_MOCK.server().stubFor(post(urlPathMatching("/openai/deployments/.*"))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody(SUCCESS_BODY)));

            var request = structuredContentRequest(nonVisionModel);
            var raw = postChatCompletion(workspaceName, request);
            var status = raw.getStatus();
            var responseBody = raw.readEntity(String.class);

            var upstreamBody = upstreamBodyOrFail(status, responseBody);

            // Non-vision model → normalizer flattens array to string. Upstream never sees the
            // typed array. AC #5 asks for strict pass-through; a `strict_pass_through` config
            // flag on the Custom LLM provider would let customers opt out of the normalizer.
            // When that flag ships, flip this assertion.
            assertThat(status).isEqualTo(HttpStatus.SC_OK);
            assertThat(upstreamBody)
                    .as("content should be a flat string today for unknown non-vision custom models")
                    .doesNotContain("\"type\" : \"text\"");
        }

        private ChatCompletionRequest structuredContentRequest(String model) {
            var structuredUserMessage = UserMessage.builder()
                    .content(List.of(Content.builder()
                            .type(ContentType.TEXT)
                            .text("Knock knock.")
                            .build()))
                    .build();
            return ChatCompletionRequest.builder()
                    .stream(false)
                    .model(model)
                    .messages(List.<Message>of(structuredUserMessage))
                    .build();
        }

        private String upstreamBodyOrFail(int status, String responseBody) {
            var upstreamRequests = WIRE_MOCK.server().findAll(postRequestedFor(
                    urlPathMatching("/openai/deployments/.*")));
            assertThat(upstreamRequests)
                    .as("Opik should forward to upstream; status=" + status + " response=" + responseBody)
                    .isNotEmpty();
            return upstreamRequests.getFirst().getBodyAsString();
        }

        private void createCustomProvider(String workspaceName) {
            createCustomProvider(workspaceName, WIRE_MOCK.runtimeInfo().getHttpBaseUrl());
        }

        private void createCustomProvider(String workspaceName, String baseUrl) {
            // LlmProviderFactoryImpl.isModelConfiguredForProvider matches CUSTOM_LLM providers
            // by scanning configuration["models"] for the full model identifier. Both provider_name
            // and models must be present or lookup fails with "API key not configured".
            var providerApiKey = ProviderApiKey.builder()
                    .provider(LlmProvider.CUSTOM_LLM)
                    .providerName(CUSTOM_PROVIDER_NAME)
                    .apiKey("dummy-key")
                    .baseUrl(baseUrl)
                    .configuration(Map.of(
                            "provider_name", CUSTOM_PROVIDER_NAME,
                            "models", MODEL))
                    .build();
            llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, API_KEY, workspaceName,
                    HttpStatus.SC_CREATED);
        }

        private static final String SUCCESS_BODY = """
                {"id":"chatcmpl-x","object":"chat.completion","created":1,"model":"gpt-4o",\
                "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""";

        private jakarta.ws.rs.core.Response postChatCompletion(String workspaceName,
                ChatCompletionRequest request) {
            return clientSupport.target(TestUtils.getBaseUrl(clientSupport) + CHAT_COMPLETIONS_PATH)
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(request));
        }
    }

}
