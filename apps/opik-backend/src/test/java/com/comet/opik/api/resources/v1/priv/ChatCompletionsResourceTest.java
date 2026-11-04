package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.LogCaptureUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ChatCompletionsClient;
import com.comet.opik.api.resources.utils.resources.LlmProviderApiKeyResourceClient;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.MessageContentNormalizer;
import com.comet.opik.domain.llm.langchain4j.AudioUrl;
import com.comet.opik.domain.llm.langchain4j.OpikContent;
import com.comet.opik.domain.llm.langchain4j.OpikContentType;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.domain.llm.langchain4j.VideoUrl;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.llm.antropic.AnthropicModelName;
import com.comet.opik.infrastructure.llm.gemini.GeminiModelName;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModelName;
import com.comet.opik.infrastructure.llm.vertexai.VertexAIModelName;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Content;
import dev.langchain4j.model.openai.internal.chat.ContentType;
import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.Role;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import io.dropwizard.jersey.errors.ErrorMessage;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static com.comet.opik.domain.llm.ChatCompletionService.ERROR_EMPTY_MESSAGES;
import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
import static com.comet.opik.domain.llm.LlmProviderFactory.ERROR_MODEL_NOT_SUPPORTED;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.headRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/// For some providers, the tests need to make actual LLM calls. For that to
/// work, the relevant API keys must be set in the environment prior to running
/// the tests. If an environment variable for a specific provider is not set, the
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
    private LlmProviderClientConfig llmProviderClientConfig;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, Injector injector) {

        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(WORKSPACE_NAME, WORKSPACE_ID);

        this.chatCompletionsClient = new ChatCompletionsClient(clientSupport);
        this.llmProviderApiKeyResourceClient = new LlmProviderApiKeyResourceClient(clientSupport);
        this.clientSupport = clientSupport;
        this.llmProviderClientConfig = injector.getInstance(OpikConfiguration.class).getLlmProviderClient();
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

        @Test
        void createAndStreamResponseReturnsBadRequestWhenNoMessages() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            createLlmProviderApiKey(workspaceName, LlmProvider.ANTHROPIC, UUID.randomUUID().toString());

            // The streaming twin of createAnthropicValidateMandatoryFields, which pins stream(false) and so cannot
            // catch a regression on this path. LlmProviderAnthropic.generateStream validates messages inline before
            // any provider I/O, which is why a throwaway API key still reaches the rejection. This must stay a real
            // HTTP 400 rather than a 200 carrying the error inside the SSE body: nothing upstream validates messages,
            // and callers branch on the status.
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                    .stream(true)
                    .model(AnthropicModelName.CLAUDE_SONNET_3_7.toString())
                    .maxCompletionTokens(100)
                    .build();

            var errorMessage = chatCompletionsClient.createAndStreamError(API_KEY, workspaceName, request,
                    HttpStatus.SC_BAD_REQUEST);

            assertThat(errorMessage.getCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(errorMessage.getMessage()).containsIgnoringCase(ERROR_EMPTY_MESSAGES);
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
                        ERROR_EMPTY_MESSAGES));
    }

    private void createLlmProviderApiKey(String workspaceName) {
        createLlmProviderApiKey(workspaceName, LlmProvider.OPEN_AI, UUID.randomUUID().toString());
    }

    private void createLlmProviderApiKey(String workspaceName, LlmProvider llmProvider, String llmProviderApiKey) {
        llmProviderApiKeyResourceClient.createProviderApiKey(
                llmProviderApiKey, llmProvider, API_KEY, workspaceName, 201);
    }

    /// Validates that 4xx errors from the upstream LLM provider gateway reach the
    /// API caller intact. Covers both the OpenAI-compatible error body shape (which
    /// current
    /// [com.comet.opik.infrastructure.llm.customllm.CustomLlmErrorMessage] expects)
    /// and the Azure OpenAI error body shape (nested error object). Relevant to
    /// OPIK-4551 AC #9.
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

        private Stream<Arguments> rawUpstreamBodyIsNotEchoed() {
            var text = UUID.randomUUID().toString();
            return Stream.of(
                    arguments(HttpStatus.SC_BAD_GATEWAY, "text/plain", "internal page " + text,
                            HttpStatus.SC_BAD_GATEWAY),
                    arguments(HttpStatus.SC_BAD_GATEWAY, "application/json", "{\"internal\": \"%s\"}".formatted(text),
                            HttpStatus.SC_BAD_GATEWAY),
                    // redirects aren't followed, and a 3xx is no provider error status, so the call fails as Opik's
                    arguments(HttpStatus.SC_MOVED_TEMPORARILY, "text/html", "<a href=\"/%s\">moved</a>".formatted(text),
                            HttpStatus.SC_INTERNAL_SERVER_ERROR));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("a body that isn't a provider error is not echoed back")
        void rawUpstreamBodyIsNotEchoed(int upstreamStatus, String contentType, String upstreamBody,
                int expectedStatus) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            createCustomProvider(workspaceName);
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .willReturn(aResponse()
                            .withStatus(upstreamStatus)
                            .withHeader("Content-Type", contentType)
                            .withBody(upstreamBody)));
            var expectedError = new ErrorMessage(expectedStatus, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);

            var request = ChatCompletionRequest.builder().stream(false).model(MODEL).addUserMessage("ping").build();
            var actualError = chatCompletionsClient.create(API_KEY, workspaceName, request, expectedStatus);
            assertThat(actualError).isEqualTo(expectedError);

            var streamed = ChatCompletionRequest.builder().stream(true).model(MODEL).addUserMessage("ping").build();
            var actualStreamedErrors = chatCompletionsClient.createAndGetStreamedError(API_KEY, workspaceName,
                    streamed);
            assertThat(actualStreamedErrors).containsExactly(expectedError);
        }

        /**
         * A caller that goes away mid-stream: the provider call is cancelled instead of being read to its end. The
         * provider is a raw server sending a chunk every 100 ms for 30 seconds, which notes when its writes start
         * failing, that is when Opik closed the connection.
         */
        @Test
        @DisplayName("a caller that disconnects mid-stream cancels the provider call")
        void callerDisconnectCancelsTheProviderCall() throws Exception {
            var providerClosed = new CompletableFuture<Long>();
            long startNanos = System.nanoTime();
            try (var provider = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                Thread.ofVirtual().start(() -> {
                    try (Socket socket = provider.accept(); var out = socket.getOutputStream()) {
                        var in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                                StandardCharsets.UTF_8));
                        int contentLength = 0;
                        String line;
                        while ((line = in.readLine()) != null && !line.isEmpty()) {
                            if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                                contentLength = Integer.parseInt(line.substring(15).trim());
                            }
                        }
                        in.skip(contentLength);
                        out.write("""
                                HTTP/1.1 200 OK\r
                                Content-Type: text/event-stream\r
                                Connection: close\r
                                \r
                                """.getBytes(StandardCharsets.UTF_8));
                        for (int i = 0; i < 300; i++) {
                            out.write("""
                                    data: {"id":"c","object":"chat.completion.chunk","created":1,"model":"gpt-4o",\
                                    "choices":[{"index":0,"delta":{"content":"x"}}]}

                                    """.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            Thread.sleep(100);
                        }
                        providerClosed.complete(-1L);
                    } catch (IOException exception) {
                        providerClosed.complete(System.nanoTime() - startNanos);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
                var workspaceName = RandomStringUtils.randomAlphanumeric(20);
                mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
                createCustomProvider(workspaceName, "http://localhost:%d".formatted(provider.getLocalPort()));
                var request = ChatCompletionRequest.builder().stream(true).model(MODEL).addUserMessage("ping")
                        .build();
                var connection = (HttpURLConnection) URI.create(
                        TestUtils.getBaseUrl(clientSupport) + CHAT_COMPLETIONS_PATH).toURL().openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty(HttpHeaders.AUTHORIZATION, API_KEY);
                connection.setRequestProperty(RequestContext.WORKSPACE_HEADER, workspaceName);
                connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
                connection.getOutputStream().write(JsonUtils.writeValueAsBytes(request));
                var reader = new BufferedReader(new InputStreamReader(connection.getInputStream(),
                        StandardCharsets.UTF_8));
                var actualFirstLine = reader.readLine();

                // the caller goes away after the first chunk
                connection.disconnect();
                long actualProviderClosedNanos = providerClosed.get(20, TimeUnit.SECONDS);

                assertThat(actualFirstLine).startsWith("data: ");
                assertThat(actualProviderClosedNanos).isBetween(0L, TimeUnit.SECONDS.toNanos(15));
            }
        }

        private int providerCalls() {
            return WIRE_MOCK.server().findAll(postRequestedFor(urlPathMatching(CHAT_COMPLETIONS_REGEX))).size();
        }

        /**
         * The provider calls made are counted after the plain request and again after the stream: the plain request
         * is attempted three times under ChatCompletionService's retry policy when its status is transient, and once
         * when it is not; the stream once.
         */
        private Stream<Arguments> providerTextIsNeverLogged() {
            var marker = "upstream-marker-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));
            var generic = new ErrorMessage(HttpStatus.SC_BAD_GATEWAY, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
            return Stream.of(
                    // not a provider error: neither returned nor logged
                    arguments(HttpStatus.SC_BAD_GATEWAY, "text/html", "<html><body>%s</body></html>".formatted(marker),
                            marker, generic, generic, List.of(3, 4)),
                    // a documented provider error: its validated message is returned, but never logged
                    arguments(HttpStatus.SC_BAD_REQUEST, "application/json",
                            JsonUtils.writeValueAsString(Map.of("error", Map.of("message", marker, "type",
                                    "invalid_request_error"))),
                            marker,
                            new ErrorMessage(HttpStatus.SC_BAD_REQUEST, marker),
                            new ErrorMessage(HttpStatus.SC_BAD_REQUEST, marker, "Custom Provider error"),
                            List.of(1, 2)),
                    // a status no retry can change, with a body that isn't a provider error
                    arguments(HttpStatus.SC_NOT_FOUND, "text/html", "<html><body>%s</body></html>".formatted(marker),
                            marker, new ErrorMessage(HttpStatus.SC_NOT_FOUND, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER),
                            new ErrorMessage(HttpStatus.SC_NOT_FOUND, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER),
                            List.of(1, 2)));
        }

        /**
         * The provider's reply reaches the server logs neither as a message nor through a logged throwable, from any
         * logger, the libraries' included.
         */
        @ParameterizedTest
        @MethodSource
        @DisplayName("what a provider answers is never written to the server logs, streamed or not")
        void providerTextIsNeverLogged(int upstreamStatus, String contentType, String upstreamBody, String marker,
                ErrorMessage expectedError, ErrorMessage expectedStreamedError, List<Integer> expectedProviderCalls) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            createCustomProvider(workspaceName);
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .willReturn(aResponse()
                            .withStatus(upstreamStatus)
                            .withHeader("Content-Type", contentType)
                            .withBody(upstreamBody)));
            var request = ChatCompletionRequest.builder().stream(false).model(MODEL).addUserMessage("ping").build();
            var streamed = ChatCompletionRequest.builder().stream(true).model(MODEL).addUserMessage("ping").build();
            var actualErrors = new ArrayList<ErrorMessage>();
            var actualProviderCalls = new ArrayList<Integer>();

            var actualEvents = LogCaptureUtils.logEventsOf(() -> {
                actualErrors.add(chatCompletionsClient.create(API_KEY, workspaceName, request,
                        expectedError.getCode()));
                actualProviderCalls.add(providerCalls());
                actualErrors.addAll(chatCompletionsClient.createAndGetStreamedError(API_KEY, workspaceName,
                        streamed));
                actualProviderCalls.add(providerCalls());
            });

            assertThat(actualErrors).isEqualTo(List.of(expectedError, expectedStreamedError));
            assertThat(actualProviderCalls).isEqualTo(expectedProviderCalls);
            assertThat(actualEvents).isNotEmpty().noneMatch(event -> event.contains(marker));
        }

        /**
         * Runs {@code test} with a retry budget of {@code maxRetries}, read by the service on every call, and restores
         * the configured one after it.
         */
        private void withRetryBudget(int maxRetries, Runnable test) {
            var configuredRetries = llmProviderClientConfig.getMaxAttempts();
            llmProviderClientConfig.setMaxAttempts(maxRetries);
            try {
                test.run();
            } finally {
                llmProviderClientConfig.setMaxAttempts(configuredRetries);
            }
        }

        private Stream<Arguments> aTransientFailureIsRetriedUpToTheBudget() {
            var failures = List.of(
                    named("an upstream 500", aResponse()
                            .withStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR)
                            .withHeader("Content-Type", "text/plain")
                            .withBody(RandomStringUtils.secure().nextAlphanumeric(20))),
                    named("a connection reset", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
            return failures.stream()
                    .flatMap(failure -> Stream.of(1, 2).map(maxRetries -> arguments(failure, maxRetries)));
        }

        /**
         * A failure another attempt can fix is retried: the provider fails every attempt the budget allows to fail,
         * and the last one succeeds. The budget can't be 0, as the configuration requires at least 1.
         */
        @ParameterizedTest(name = "{0}, maxRetries={1}")
        @MethodSource
        @DisplayName("a transient provider failure is retried up to the retry budget")
        void aTransientFailureIsRetriedUpToTheBudget(ResponseDefinitionBuilder failure, int maxRetries) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            createCustomProvider(workspaceName);
            var scenario = UUID.randomUUID().toString();
            var successBody = """
                    {"id":"chatcmpl-%s","object":"chat.completion","created":%d,"model":"gpt-4o",\
                    "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}]}"""
                    .formatted(RandomStringUtils.secure().nextAlphanumeric(10), System.currentTimeMillis() / 1000,
                            RandomStringUtils.secure().nextAlphanumeric(20));
            IntStream.range(0, maxRetries).forEach(failed -> WIRE_MOCK.server().stubFor(
                    post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                            .inScenario(scenario)
                            .whenScenarioStateIs(failed == 0 ? Scenario.STARTED : "failed %d".formatted(failed))
                            .willReturn(failure)
                            .willSetStateTo("failed %d".formatted(failed + 1))));
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .inScenario(scenario)
                    .whenScenarioStateIs("failed %d".formatted(maxRetries))
                    .willReturn(okJson(successBody)));
            var request = ChatCompletionRequest.builder().stream(false).model(MODEL).addUserMessage("ping").build();
            var expectedResponse = JsonUtils.readValue(successBody, ChatCompletionResponse.class);
            var expectedProviderCalls = maxRetries + 1;

            withRetryBudget(maxRetries, () -> {
                var actualResponse = chatCompletionsClient.create(API_KEY, workspaceName, request);

                assertThat(actualResponse).isEqualTo(expectedResponse);
                assertThat(providerCalls()).isEqualTo(expectedProviderCalls);
            });
        }

        /**
         * A failure that isn't recognised as transient fails on its first attempt, whatever the budget, as another one
         * could repeat a call the provider already acted on: here a reply that can't be parsed.
         */
        @ParameterizedTest(name = "maxRetries={0}")
        @ValueSource(ints = {1, 2})
        @DisplayName("a failure that isn't transient is not retried")
        void aFailureThatIsNotTransientIsNotRetried(int maxRetries) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            createCustomProvider(workspaceName);
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"choices\": %s".formatted(RandomStringUtils.secure().nextAlphanumeric(20)))));
            var request = ChatCompletionRequest.builder().stream(false).model(MODEL).addUserMessage("ping").build();
            var expectedError = new ErrorMessage(HttpStatus.SC_INTERNAL_SERVER_ERROR,
                    UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
            var expectedProviderCalls = 1;

            withRetryBudget(maxRetries, () -> {
                var actualError = chatCompletionsClient.create(API_KEY, workspaceName, request,
                        HttpStatus.SC_INTERNAL_SERVER_ERROR);

                assertThat(actualError).isEqualTo(expectedError);
                assertThat(providerCalls()).isEqualTo(expectedProviderCalls);
            });
        }

        /**
         * A provider that resets the connection once it took the request may have acted on it. A transport failure is
         * transient, so ChatCompletionService's retry budget sends the request again, but the HTTP client never does
         * on its own: the provider sees one request per attempt, the first one sent on the connection the previous
         * call left pooled, and one for the stream, which isn't retried.
         */
        @ParameterizedTest(name = "maxRetries={0}")
        @ValueSource(ints = {1, 2})
        @DisplayName("a connection reset after the request is sent costs one provider call per attempt")
        void aConnectionResetCostsOneProviderCallPerAttempt(int maxRetries) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            createCustomProvider(workspaceName);
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX)).willReturn(okJson(SUCCESS_BODY)));
            var request = ChatCompletionRequest.builder().stream(false).model(MODEL).addUserMessage("ping").build();
            var streamed = ChatCompletionRequest.builder().stream(true).model(MODEL).addUserMessage("ping").build();
            var expectedError = new ErrorMessage(HttpStatus.SC_INTERNAL_SERVER_ERROR,
                    UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
            var expectedProviderCalls = List.of(1, maxRetries + 2, maxRetries + 3);
            var actualProviderCalls = new ArrayList<Integer>();

            withRetryBudget(maxRetries, () -> {
                chatCompletionsClient.create(API_KEY, workspaceName, request);
                actualProviderCalls.add(providerCalls());
                WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

                var actualError = chatCompletionsClient.create(API_KEY, workspaceName, request,
                        HttpStatus.SC_INTERNAL_SERVER_ERROR);
                actualProviderCalls.add(providerCalls());
                var actualStreamedErrors = chatCompletionsClient.createAndGetStreamedError(API_KEY, workspaceName,
                        streamed);
                actualProviderCalls.add(providerCalls());

                assertThat(actualError).isEqualTo(expectedError);
                assertThat(actualStreamedErrors).containsExactly(expectedError);
            });

            assertThat(actualProviderCalls).isEqualTo(expectedProviderCalls);
        }

        /**
         * A provider's header values are sent, but never logged: not when the provider is saved or updated, nor when
         * it's called.
         */
        @Test
        @DisplayName("a custom provider's header values reach the provider but never the logs")
        void providerHeaderValuesAreNeverLogged() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            var headerName = "X-Route-%s".formatted(RandomStringUtils.secure().nextAlphabetic(8));
            var savedValue = "saved-marker-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));
            var updatedValue = "updated-marker-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX)).willReturn(okJson(SUCCESS_BODY)));
            var request = ChatCompletionRequest.builder().stream(false).model(MODEL).addUserMessage("ping").build();
            var expectedContent = "ok";
            var actualContents = new ArrayList<String>();

            var actualEvents = LogCaptureUtils.logEventsOf(() -> {
                var providerApiKey = llmProviderApiKeyResourceClient.createProviderApiKey(ProviderApiKey.builder()
                        .provider(LlmProvider.CUSTOM_LLM)
                        .providerName(CUSTOM_PROVIDER_NAME)
                        .apiKey("dummy-key")
                        .baseUrl(WIRE_MOCK.runtimeInfo().getHttpBaseUrl())
                        .headers(Map.of(headerName, savedValue))
                        .configuration(Map.of("provider_name", CUSTOM_PROVIDER_NAME, "models", MODEL))
                        .build(), API_KEY, workspaceName, HttpStatus.SC_CREATED);
                llmProviderApiKeyResourceClient.updateProviderApiKey(providerApiKey.id(), ProviderApiKeyUpdate.builder()
                        .apiKey("dummy-key")
                        .headers(Map.of(headerName, updatedValue))
                        .build(), API_KEY, workspaceName, HttpStatus.SC_NO_CONTENT);
                actualContents.add(chatCompletionsClient.create(API_KEY, workspaceName, request).choices()
                        .getFirst().message().content());
            });

            assertThat(actualContents).containsExactly(expectedContent);
            WIRE_MOCK.server().verify(1, postRequestedFor(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .withHeader(headerName, equalTo(updatedValue)));
            assertThat(actualEvents).isNotEmpty()
                    .noneMatch(event -> event.contains(savedValue) || event.contains(updatedValue));
        }

        /**
         * An error body far past providerErrorBodyMaxSize, dripped over a minute: only the capped prefix is read and
         * the rest is never waited for, so both calls end long before the body would.
         */
        @Test
        @DisplayName("a long provider error body is read only up to its cap, and not echoed back")
        void longErrorBodyIsReadOnlyUpToItsCap() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            createCustomProvider(workspaceName);
            var longBody = """
                    {"error": {"type": "invalid_request_error", "message": "%s"}}"""
                    .formatted(RandomStringUtils.secure().nextAlphanumeric(4 * 1024 * 1024));
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_BAD_REQUEST)
                            .withHeader("Content-Type", "application/json")
                            .withBody(longBody)
                            .withChunkedDribbleDelay(600, 60_000)));
            var expectedError = new ErrorMessage(HttpStatus.SC_BAD_REQUEST, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
            long expectedMaxElapsedMillis = 20_000;
            long startNanos = System.nanoTime();

            var request = ChatCompletionRequest.builder().stream(false).model(MODEL).addUserMessage("ping").build();
            var actualError = chatCompletionsClient.create(API_KEY, workspaceName, request,
                    HttpStatus.SC_BAD_REQUEST);
            var streamed = ChatCompletionRequest.builder().stream(true).model(MODEL).addUserMessage("ping").build();
            var actualStreamedErrors = chatCompletionsClient.createAndGetStreamedError(API_KEY, workspaceName,
                    streamed);

            long actualElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertThat(actualError).isEqualTo(expectedError);
            assertThat(actualStreamedErrors).containsExactly(expectedError);
            assertThat(actualElapsedMillis).isLessThan(expectedMaxElapsedMillis);
        }

        private Stream<Arguments> providerErrorMessageIsValidated() {
            var text = UUID.randomUUID().toString();
            var generic = UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
            var details = "Custom Provider error";
            return Stream.of(
                    arguments("invalid_request_error", "deployment not found " + text, "deployment not found " + text,
                            details),
                    arguments("invalid_request_error", "deployment not found\n" + text, generic, details),
                    arguments("invalid_request_error", "deployment not found <b>" + text + "</b>", generic, details),
                    // the boundaries of what is refused: C0 and C1 controls, DEL and the Unicode line separators
                    arguments("invalid_request_error", "deployment not found\u0000%s".formatted(text), generic,
                            details),
                    arguments("invalid_request_error", "deployment not found\t%s".formatted(text), generic, details),
                    arguments("invalid_request_error", "deployment not found\u007F%s".formatted(text), generic,
                            details),
                    arguments("invalid_request_error", "deployment not found\u0085%s".formatted(text), generic,
                            details),
                    arguments("invalid_request_error", "deployment not found\u009F%s".formatted(text), generic,
                            details),
                    arguments("invalid_request_error", "deployment not found\u2028%s".formatted(text), generic,
                            details),
                    arguments("invalid_request_error", "deployment not found\u2029%s".formatted(text), generic,
                            details),
                    // markup: a tag, a closing tag, a comment, a declaration or a processing instruction
                    arguments("invalid_request_error", "deployment not found </div> %s".formatted(text), generic,
                            details),
                    arguments("invalid_request_error", "deployment not found <!-- %s".formatted(text), generic,
                            details),
                    arguments("invalid_request_error", "deployment not found <é %s".formatted(text), generic, details),
                    arguments("invalid_request_error",
                            "deployment not found <?xml version=\"1.0\"?> %s".formatted(text),
                            generic, details),
                    // and comparisons, which aren't markup
                    arguments("invalid_request_error", "Expected a value >= 1, got 0 %s".formatted(text),
                            "Expected a value >= 1, got 0 %s".formatted(text), details),
                    arguments("invalid_request_error", "Expected a value <= 2 or < 5 and > 0 %s".formatted(text),
                            "Expected a value <= 2 or < 5 and > 0 %s".formatted(text), details),
                    // and of what is kept: the first printable character past C1, and characters outside the BMP
                    arguments("invalid_request_error", "deployment not found\u00A0%s".formatted(text),
                            "deployment not found\u00A0%s".formatted(text), details),
                    arguments("invalid_request_error", "deployment not found \uD83D\uDE00 %s".formatted(text),
                            "deployment not found \uD83D\uDE00 %s".formatted(text), details),
                    // one past providerErrorMessageMaxLength in config-test.yml
                    arguments("invalid_request_error", RandomStringUtils.randomAlphanumeric(301), generic, details),
                    // not a type OpenAI documents
                    arguments("gateway_error", "deployment not found " + text, generic, null));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("a provider error message is passed on only when its type is documented and it is a short plain message")
        void providerErrorMessageIsValidated(String type, String providerMessage, String message, String details) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            createCustomProvider(workspaceName);
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_NOT_FOUND)
                            .withHeader("Content-Type", "application/json")
                            .withBody(JsonUtils.writeValueAsString(
                                    Map.of("error", Map.of("message", providerMessage, "type", type))))));

            var expectedError = new ErrorMessage(HttpStatus.SC_BAD_REQUEST, message);
            var expectedStreamedError = new ErrorMessage(HttpStatus.SC_BAD_REQUEST, message, details);

            var request = ChatCompletionRequest.builder().stream(false).model(MODEL).addUserMessage("ping").build();
            var actualError = chatCompletionsClient.create(API_KEY, workspaceName, request, HttpStatus.SC_BAD_REQUEST);
            assertThat(actualError).isEqualTo(expectedError);

            var streamed = ChatCompletionRequest.builder().stream(true).model(MODEL).addUserMessage("ping").build();
            var actualStreamedErrors = chatCompletionsClient.createAndGetStreamedError(API_KEY, workspaceName,
                    streamed);
            assertThat(actualStreamedErrors).containsExactly(expectedStreamedError);
        }

        @Test
        @DisplayName("a custom provider is called over HTTP/1.1 and its redirect isn't followed, streamed or not")
        void customProviderIsCalledOverHttp11WithoutFollowingRedirects() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            createCustomProvider(workspaceName);
            var redirectTarget = "/elsewhere-" + UUID.randomUUID();
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .willReturn(aResponse().withStatus(307).withHeader("Location", redirectTarget)));
            WIRE_MOCK.server().stubFor(any(urlPathEqualTo(redirectTarget))
                    .willReturn(okJson(SUCCESS_BODY)));
            var expectedError = new ErrorMessage(HttpStatus.SC_INTERNAL_SERVER_ERROR,
                    "Unexpected error calling LLM provider");
            var expectedProtocol = "HTTP/1.1";
            var request = ChatCompletionRequest.builder().stream(false).model(MODEL).addUserMessage("ping").build();
            var streamed = ChatCompletionRequest.builder().stream(true).model(MODEL).addUserMessage("ping").build();

            var actualError = chatCompletionsClient.create(API_KEY, workspaceName, request,
                    HttpStatus.SC_INTERNAL_SERVER_ERROR);
            var actualStreamedErrors = chatCompletionsClient.createAndGetStreamedError(API_KEY, workspaceName,
                    streamed);

            assertThat(actualError).isEqualTo(expectedError);
            assertThat(actualStreamedErrors).containsExactly(expectedError);
            WIRE_MOCK.server().verify(0, anyRequestedFor(urlPathEqualTo(redirectTarget)));
            var actualRequests = WIRE_MOCK.server().findAll(postRequestedFor(urlPathMatching(CHAT_COMPLETIONS_REGEX)));
            assertThat(actualRequests).extracting(LoggedRequest::getProtocol).containsOnly(expectedProtocol);
        }

        @Test
        @DisplayName("a custom provider is asked for an uncompressed body, and a compressed one isn't inflated")
        void customProviderCompressedReplyIsNotInflated() throws Exception {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            createCustomProvider(workspaceName);
            var compressed = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(compressed)) {
                gzip.write(SUCCESS_BODY.getBytes(StandardCharsets.UTF_8));
            }
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withHeader("Content-Encoding", "gzip")
                            .withBody(compressed.toByteArray())));

            var request = ChatCompletionRequest.builder().stream(false).model(MODEL).addUserMessage("ping").build();
            var expectedError = new ErrorMessage(HttpStatus.SC_INTERNAL_SERVER_ERROR,
                    "Unexpected error calling LLM provider");

            var actualError = chatCompletionsClient.create(API_KEY, workspaceName, request,
                    HttpStatus.SC_INTERNAL_SERVER_ERROR);

            assertThat(actualError).isEqualTo(expectedError);
            var expectedAcceptEncoding = "identity";
            var actualRequests = WIRE_MOCK.server().findAll(postRequestedFor(urlPathMatching(CHAT_COMPLETIONS_REGEX)));
            assertThat(actualRequests)
                    .extracting(loggedRequest -> loggedRequest.getHeader("Accept-Encoding"))
                    .containsOnly(expectedAcceptEncoding);
        }

        @Test
        @DisplayName("OpenAI-style {error:string} body — names no documented error, so only the status (400) is passed on")
        void openAiStyleErrorBodyIsNotPropagated() {
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

            // CustomLlmErrorMessage.toErrorMessage() hardcodes 400 regardless of upstream status
            var expectedError = new ErrorMessage(HttpStatus.SC_BAD_REQUEST, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);

            var actualError = chatCompletionsClient.create(API_KEY, workspaceName, request, HttpStatus.SC_BAD_REQUEST);

            assertThat(actualError).isEqualTo(expectedError);
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

        private Stream<Arguments> azureStyleErrorBody() {
            var message = "The API version 2024-08-01-preview is not supported by this service.";
            return Stream.of(
                    arguments("InvalidAPIVersion", message, new ErrorMessage(HttpStatus.SC_BAD_REQUEST, message)),
                    arguments("DeploymentNotFound", "The API deployment for this resource does not exist.",
                            new ErrorMessage(HttpStatus.SC_BAD_REQUEST, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("Azure-style {error:{code,message}} body — InvalidAPIVersion keeps its message, a deployment code doesn't")
        void azureStyleErrorBody(String code, String message, ErrorMessage expectedError) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            createCustomProvider(workspaceName);

            // Real Azure OpenAI error payload shape — nested object, not a string.
            var upstreamBody = JsonUtils.writeValueAsString(Map.of("error", Map.of("code", code, "message", message)));
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

            var actualError = chatCompletionsClient.create(API_KEY, workspaceName, request, HttpStatus.SC_BAD_REQUEST);

            assertThat(actualError).isEqualTo(expectedError);
        }

        @Test
        @DisplayName("Azure content filter body — a code Azure OpenAI documents surfaces with its message")
        void azureContentFilterErrorBodyIsPropagated() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            createCustomProvider(workspaceName);
            var message = "The response was filtered " + UUID.randomUUID();
            // the shape Azure OpenAI documents for a prompt its content filter refuses
            var upstreamBody = """
                    {"error": {"message": "%s", "type": null, "param": "prompt", "code": "content_filter", \
                    "status": 400}}""".formatted(message);
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_BAD_REQUEST)
                            .withHeader("Content-Type", "application/json")
                            .withBody(upstreamBody)));
            var request = ChatCompletionRequest.builder().stream(false).model(MODEL).addUserMessage("ping").build();
            var expectedError = new ErrorMessage(HttpStatus.SC_BAD_REQUEST, message);

            var actualError = chatCompletionsClient.create(API_KEY, workspaceName, request, HttpStatus.SC_BAD_REQUEST);

            assertThat(actualError).isEqualTo(expectedError);
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

    /// What reaches a caller when OpenAI or Anthropic answers with an error: the
    /// provider's message only for an error type or code it documents, otherwise the
    /// generic message with the status unchanged.
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Provider error messages")
    class ProviderErrorMessages {

        private static final String OPENAI_MODEL = "gpt-4o";
        private static final String ANTHROPIC_MODEL = "claude-sonnet-4-6";
        private static final String CHAT_COMPLETIONS_REGEX = ".*/chat/completions.*";
        private static final String MESSAGES_PATH = "/v1/messages";

        @BeforeEach
        void resetUpstreamStubs() {
            WIRE_MOCK.server().resetAll();
        }

        /** What every provider answers in this class, which must reach the caller only, never the server logs. */
        private final String message = "provider says %s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));

        private Stream<Arguments> openAiErrors() {
            return Stream.of(
                    arguments(named("documented code", HttpStatus.SC_UNAUTHORIZED), "application/json",
                            openAiError(message, "invalid_request_error", "invalid_api_key"),
                            new ErrorMessage(HttpStatus.SC_UNAUTHORIZED, message)),
                    arguments(named("documented code beside a top-level status", HttpStatus.SC_UNAUTHORIZED),
                            "application/json",
                            openAiErrorWithStatus(message, "invalid_request_error", "invalid_api_key",
                                    HttpStatus.SC_UNAUTHORIZED),
                            new ErrorMessage(HttpStatus.SC_UNAUTHORIZED, message)),
                    // the 500 is what OpenAiErrorMessage reports for a code it can't map, as before
                    arguments(named("undocumented type and code", HttpStatus.SC_BAD_REQUEST), "application/json",
                            openAiError(message, "gateway_error", "blocked"),
                            new ErrorMessage(HttpStatus.SC_INTERNAL_SERVER_ERROR,
                                    UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)),
                    arguments(named("unparseable body", HttpStatus.SC_FORBIDDEN), "text/plain", message,
                            new ErrorMessage(HttpStatus.SC_FORBIDDEN, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)));
        }

        @ParameterizedTest(name = "OpenAI {0}")
        @MethodSource("openAiErrors")
        void openAiError(int upstreamStatus, String contentType, String upstreamBody, ErrorMessage expectedError) {
            var workspaceName = prepareWorkspace(LlmProvider.OPEN_AI, WIRE_MOCK.runtimeInfo().getHttpBaseUrl());
            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .willReturn(aResponse()
                            .withStatus(upstreamStatus)
                            .withHeader("Content-Type", contentType)
                            .withBody(upstreamBody)));

            assertErrors(workspaceName, OPENAI_MODEL, expectedError);
        }

        private Stream<Arguments> anthropicErrors() {
            return Stream.of(
                    arguments(named("documented type", HttpStatus.SC_NOT_FOUND), "application/json",
                            anthropicError("not_found_error", message),
                            new ErrorMessage(HttpStatus.SC_NOT_FOUND, message)),
                    arguments(named("undocumented type", HttpStatus.SC_BAD_REQUEST), "application/json",
                            anthropicError("proxy_error", message),
                            new ErrorMessage(HttpStatus.SC_BAD_REQUEST, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)),
                    arguments(named("unparseable body", HttpStatus.SC_BAD_REQUEST), "text/plain", message,
                            new ErrorMessage(HttpStatus.SC_BAD_REQUEST, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)),
                    arguments(named("documented server error", HttpStatus.SC_INTERNAL_SERVER_ERROR),
                            "application/json", anthropicError("api_error", message),
                            new ErrorMessage(HttpStatus.SC_INTERNAL_SERVER_ERROR, message)));
        }

        @ParameterizedTest(name = "Anthropic {0}")
        @MethodSource("anthropicErrors")
        void anthropicError(int upstreamStatus, String contentType, String upstreamBody,
                ErrorMessage expectedError) {
            var workspaceName = prepareWorkspace(LlmProvider.ANTHROPIC,
                    WIRE_MOCK.runtimeInfo().getHttpBaseUrl() + "/v1/");
            WIRE_MOCK.server().stubFor(post(urlPathEqualTo(MESSAGES_PATH))
                    .willReturn(aResponse()
                            .withStatus(upstreamStatus)
                            .withHeader("Content-Type", contentType)
                            .withBody(upstreamBody)));

            assertErrors(workspaceName, ANTHROPIC_MODEL, expectedError);
        }

        private Stream<Arguments> anthropicStreamErrors() {
            var message = "provider says " + UUID.randomUUID();
            return Stream.of(
                    arguments(named("documented type", "overloaded_error"), message,
                            new ErrorMessage(HttpStatus.SC_INTERNAL_SERVER_ERROR, message)),
                    arguments(named("undocumented type", "proxy_error"), message,
                            new ErrorMessage(HttpStatus.SC_INTERNAL_SERVER_ERROR,
                                    UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)));
        }

        @ParameterizedTest(name = "Anthropic error event, {0}")
        @MethodSource("anthropicStreamErrors")
        void anthropicErrorEventAfterTheStreamStarted(String type, String message, ErrorMessage expectedError) {
            var workspaceName = prepareWorkspace(LlmProvider.ANTHROPIC,
                    WIRE_MOCK.runtimeInfo().getHttpBaseUrl() + "/v1/");
            WIRE_MOCK.server().stubFor(post(urlPathEqualTo(MESSAGES_PATH))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", MediaType.SERVER_SENT_EVENTS)
                            .withBody("event: error\ndata: %s\n\n".formatted(anthropicError(type, message)))));

            var streamed = ChatCompletionRequest.builder().stream(true).model(ANTHROPIC_MODEL)
                    .maxCompletionTokens(100).addUserMessage("ping").build();

            var actualStreamedErrors = chatCompletionsClient.createAndGetStreamedError(API_KEY, workspaceName,
                    streamed);

            assertThat(actualStreamedErrors).containsExactly(expectedError);
        }

        /** The caller gets the expected error, streamed or not, and nothing the provider answered is logged. */
        private void assertErrors(String workspaceName, String model, ErrorMessage expectedError) {
            var request = ChatCompletionRequest.builder().stream(false).model(model).maxCompletionTokens(100)
                    .addUserMessage("ping").build();
            var streamed = ChatCompletionRequest.builder().stream(true).model(model).maxCompletionTokens(100)
                    .addUserMessage("ping").build();
            var actualErrors = new ArrayList<ErrorMessage>();

            var actualEvents = LogCaptureUtils.logEventsOf(() -> {
                actualErrors.add(chatCompletionsClient.create(API_KEY, workspaceName, request,
                        expectedError.getCode()));
                actualErrors.addAll(chatCompletionsClient.createAndGetStreamedError(API_KEY, workspaceName,
                        streamed));
            });

            assertThat(actualErrors).isEqualTo(List.of(expectedError, expectedError));
            assertThat(actualEvents).isNotEmpty().noneMatch(event -> event.contains(message));
        }

        private String prepareWorkspace(LlmProvider provider, String baseUrl) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            llmProviderApiKeyResourceClient.createProviderApiKey(ProviderApiKey.builder()
                    .provider(provider)
                    .apiKey("dummy-key")
                    .baseUrl(baseUrl)
                    .build(), API_KEY, workspaceName, HttpStatus.SC_CREATED);
            return workspaceName;
        }

        private static String openAiError(String message, String type, String code) {
            return JsonUtils.writeValueAsString(Map.of("error",
                    Map.of("message", message, "type", type, "code", code)));
        }

        /** As {@link #openAiError}, with the top-level {@code status} field OpenAI also sends. */
        private static String openAiErrorWithStatus(String message, String type, String code, int status) {
            return JsonUtils.writeValueAsString(Map.of("error",
                    Map.of("message", message, "type", type, "code", code), "status", status));
        }

        private static String anthropicError(String type, String message) {
            return JsonUtils.writeValueAsString(Map.of("type", "error",
                    "error", Map.of("type", type, "message", message), "request_id", "req_" + UUID.randomUUID()));
        }
    }

    /// Claude rejects temperature and top_p in the same request; Bedrock answers
    /// "temperature and top_p cannot both be specified for this model" (OPIK-8386).
    /// The rule used to live only in the Anthropic provider's mapper, but a Claude
    /// model reached through a custom gateway is dispatched by
    /// OpenAICompatibleServiceProvider, which has no such rule. These assert on what
    /// actually leaves Opik, through the real dispatch path.
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Custom LLM provider — Claude sampling parameter exclusivity")
    class CustomLlmClaudeSamplingParams {

        private static final String PROVIDER_NAME = "claude-gw";
        private static final String CLAUDE_MODEL = "custom-llm/" + PROVIDER_NAME + "/claude-opus-4-6";
        private static final String OTHER_MODEL = "custom-llm/" + PROVIDER_NAME + "/mistral-large-2411";
        private static final String CHAT_COMPLETIONS_REGEX = ".*/chat/completions.*";
        private static final String OK_BODY = """
                {"id":"chatcmpl-x","object":"chat.completion","created":1,"model":"claude-opus-4-6",\
                "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""";

        @BeforeEach
        void resetUpstreamStubs() {
            WIRE_MOCK.server().resetAll();
        }

        @Test
        @DisplayName("Claude through a custom gateway: temperature is forwarded, top_p is dropped")
        void dropsTopPForClaude() {
            var body = upstreamBodyFor(CLAUDE_MODEL);

            assertThat(body.path("temperature").asDouble()).isEqualTo(0.7);
            assertThat(body.has("top_p")).isFalse();
        }

        @Test
        @DisplayName("A model without the constraint on the same gateway keeps both")
        void keepsBothForOtherModels() {
            var body = upstreamBodyFor(OTHER_MODEL);

            assertThat(body.path("temperature").asDouble()).isEqualTo(0.7);
            assertThat(body.path("top_p").asDouble()).isEqualTo(0.9);
        }

        private JsonNode upstreamBodyFor(String model) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            createProviderFor(workspaceName, model);

            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody(OK_BODY)));

            var request = ChatCompletionRequest.builder()
                    .stream(false)
                    .model(model)
                    .temperature(0.7)
                    .topP(0.9)
                    .addUserMessage("ping")
                    .build();

            postChatCompletionFor(workspaceName, request);

            var upstream = WIRE_MOCK.server().findAll(postRequestedFor(
                    urlPathMatching(CHAT_COMPLETIONS_REGEX)));
            assertThat(upstream).as("Opik should have forwarded the request upstream").isNotEmpty();
            return JsonUtils.getJsonNodeFromString(upstream.getFirst().getBodyAsString());
        }

        private void createProviderFor(String workspaceName, String model) {
            var providerApiKey = ProviderApiKey.builder()
                    .provider(LlmProvider.CUSTOM_LLM)
                    .providerName(PROVIDER_NAME)
                    .apiKey("dummy-key")
                    .baseUrl(WIRE_MOCK.runtimeInfo().getHttpBaseUrl())
                    .configuration(Map.of("provider_name", PROVIDER_NAME, "models", model))
                    .build();
            llmProviderApiKeyResourceClient.createProviderApiKey(providerApiKey, API_KEY, workspaceName,
                    HttpStatus.SC_CREATED);
        }

        private jakarta.ws.rs.core.Response postChatCompletionFor(String workspaceName,
                ChatCompletionRequest request) {
            return clientSupport.target(TestUtils.getBaseUrl(clientSupport) + "/v1/private/chat/completions")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(request));
        }
    }

    /// The playground sends the Anthropic effort as
    /// custom_parameters.output_config.effort, which the langchain4j request DTO
    /// keeps, while Anthropic reads output_config.effort at the top level of its own
    /// body. A flat thinking_effort used to be dropped here without a trace
    /// (OPIK-8605). These post the body exactly as the frontend writes it and assert
    /// on what reaches Anthropic.
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Anthropic provider — output_config.effort")
    class AnthropicOutputConfigEffort {

        private static final String MESSAGES_PATH = "/v1/messages";
        private static final String MESSAGES_RESPONSE = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-6",\
                "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",\
                "usage":{"input_tokens":1,"output_tokens":1}}""";

        private static final String MESSAGES_STREAM = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",\
                "model":"claude-sonnet-4-6","content":[],"stop_reason":null,"usage":{"input_tokens":1,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

                event: message_stop
                data: {"type":"message_stop"}

                """;

        @BeforeEach
        void resetUpstreamStubs() {
            WIRE_MOCK.server().resetAll();
        }

        @ParameterizedTest(name = "{0} at {1}")
        @CsvSource({"claude-sonnet-4-6, low", "claude-sonnet-5, xhigh"})
        void forwardsThePlaygroundEffortToAnthropic(String model, String effort) {
            var workspaceName = prepareWorkspace();

            var response = postFrontendBody(workspaceName, model, effortParameters(effort), false);

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            var sent = WIRE_MOCK.server().findAll(postRequestedFor(urlPathEqualTo(MESSAGES_PATH)));
            assertThat(sent).hasSize(1);
            assertThat(JsonUtils.getJsonNodeFromString(sent.getFirst().getBodyAsString()).path("output_config"))
                    .isEqualTo(JsonUtils.getJsonNodeFromString("{\"effort\": \"%s\"}".formatted(effort)));
        }

        @Test
        void rejectsAStoredAdaptiveEffortWithABadRequest() {
            var workspaceName = prepareWorkspace();

            var response = postFrontendBody(workspaceName, "claude-sonnet-4-6", effortParameters("adaptive"), false);

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(response.readEntity(String.class))
                    .contains("Unsupported custom_parameters.output_config.effort for the model, "
                            + "model 'claude-sonnet-4-6', effort 'adaptive', supported '[low, medium, high, max]'");
            assertThat(WIRE_MOCK.server().findAll(postRequestedFor(urlPathEqualTo(MESSAGES_PATH)))).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void rejectsAMalformedOutputConfigWithABadRequest(
                String description, String customParameters, String expectedMessage) {
            var workspaceName = prepareWorkspace();

            var response = postFrontendBody(workspaceName, "claude-sonnet-4-6", customParameters, false);

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(response.readEntity(String.class)).contains(expectedMessage);
            assertThat(WIRE_MOCK.server().findAll(postRequestedFor(urlPathEqualTo(MESSAGES_PATH)))).isEmpty();
        }

        Stream<Arguments> rejectsAMalformedOutputConfigWithABadRequest() {
            var notAnObject = "custom_parameters.output_config must be an object, model 'claude-sonnet-4-6'";
            return Stream.of(
                    arguments("output_config as a string", "{\"output_config\": \"low\"}", notAnObject),
                    arguments("output_config as an array", "{\"output_config\": [\"low\"]}", notAnObject),
                    arguments("effort as a number", "{\"output_config\": {\"effort\": 1}}",
                            "custom_parameters.output_config.effort must be a string, "
                                    + "model 'claude-sonnet-4-6', effort '1'"),
                    arguments("effort as an object", "{\"output_config\": {\"effort\": {\"level\": \"low\"}}}",
                            "custom_parameters.output_config.effort must be a string, "
                                    + "model 'claude-sonnet-4-6', effort '{level=low}'"));
        }

        @Test
        void streamsThePlaygroundEffortToAnthropic() {
            var workspaceName = prepareWorkspace();
            WIRE_MOCK.server().stubFor(post(urlPathEqualTo(MESSAGES_PATH))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", MediaType.SERVER_SENT_EVENTS)
                            .withBody(MESSAGES_STREAM)));

            var response = postFrontendBody(workspaceName, "claude-sonnet-4-6", effortParameters("low"), true);

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            assertThat(response.readEntity(String.class)).contains("\"content\":\"ok\"");
            var sent = WIRE_MOCK.server().findAll(postRequestedFor(urlPathEqualTo(MESSAGES_PATH)));
            assertThat(sent).hasSize(1);
            var body = JsonUtils.getJsonNodeFromString(sent.getFirst().getBodyAsString());
            assertThat(body.path("stream").asBoolean()).isTrue();
            assertThat(body.path("output_config"))
                    .isEqualTo(JsonUtils.getJsonNodeFromString("{\"effort\": \"low\"}"));
        }

        @Test
        void rejectsAnUnsupportedStreamedEffortWithABadRequest() {
            var workspaceName = prepareWorkspace();

            var response = postFrontendBody(workspaceName, "claude-sonnet-4-6", effortParameters("xhigh"), true);

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(response.readEntity(String.class))
                    .contains("Unsupported custom_parameters.output_config.effort for the model, "
                            + "model 'claude-sonnet-4-6', effort 'xhigh', supported '[low, medium, high, max]'");
            assertThat(WIRE_MOCK.server().findAll(postRequestedFor(urlPathEqualTo(MESSAGES_PATH)))).isEmpty();
        }

        private String prepareWorkspace() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            llmProviderApiKeyResourceClient.createProviderApiKey(ProviderApiKey.builder()
                    .provider(LlmProvider.ANTHROPIC)
                    .apiKey("dummy-key")
                    .baseUrl(WIRE_MOCK.runtimeInfo().getHttpBaseUrl() + "/v1/")
                    .build(), API_KEY, workspaceName, HttpStatus.SC_CREATED);
            WIRE_MOCK.server().stubFor(post(urlPathEqualTo(MESSAGES_PATH))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody(MESSAGES_RESPONSE)));
            return workspaceName;
        }

        private String effortParameters(String effort) {
            return "{\"output_config\": {\"effort\": \"%s\"}}".formatted(effort);
        }

        private jakarta.ws.rs.core.Response postFrontendBody(
                String workspaceName, String model, String customParameters, boolean stream) {
            var body = """
                    {"model": "%s", "messages": [{"role": "user", "content": "ping"}], "stream": %s,
                    "max_completion_tokens": 100, "custom_parameters": %s}"""
                    .formatted(model, stream, customParameters);
            return clientSupport.target(TestUtils.getBaseUrl(clientSupport) + "/v1/private/chat/completions")
                    .request()
                    .accept(stream ? MediaType.SERVER_SENT_EVENTS_TYPE : MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(JsonUtils.getJsonNodeFromString(body)));
        }
    }

    /// OpenAI reasoning models take temperature only at its default and reject
    /// top_p and both penalties. The frontend leaves them out, but anything else
    /// that calls the endpoint relied on that. These assert on what actually leaves
    /// Opik, classified by the real model registry.
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("OpenAI reasoning models — sampling params and penalties")
    class OpenAiReasoningSamplingParams {

        private static final String CHAT_COMPLETIONS_REGEX = ".*/chat/completions.*";
        private static final String OK_BODY = """
                {"id":"chatcmpl-x","object":"chat.completion","created":1,"model":"gpt-5-mini",\
                "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""";
        private static final List<String> STRIPPED_FIELDS = List.of(
                "temperature", "top_p", "frequency_penalty", "presence_penalty");

        @BeforeEach
        void resetUpstreamStubs() {
            WIRE_MOCK.server().resetAll();
        }

        @ParameterizedTest
        @ValueSource(strings = {"gpt-5-mini", "o3"})
        @DisplayName("A reasoning model on the OpenAI provider reaches it without sampling params or penalties")
        void dropsSamplingParamsAndPenalties(String model) {
            var body = upstreamBodyFor(LlmProvider.OPEN_AI, model);

            STRIPPED_FIELDS.forEach(field -> assertThat(body.has(field)).as(field).isFalse());
            assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(100);
        }

        static Stream<Arguments> modelsThatKeepTheirParams() {
            return Stream.of(
                    arguments(LlmProvider.OPEN_AI, "gpt-4o"),
                    arguments(LlmProvider.OPEN_AI, "gpt-5-chat-latest"),
                    arguments(LlmProvider.OPEN_ROUTER, "openai/gpt-5-mini"));
        }

        @ParameterizedTest
        @MethodSource("modelsThatKeepTheirParams")
        @DisplayName("Any other model keeps every param")
        void keepsParamsForOtherModels(LlmProvider provider, String model) {
            var body = upstreamBodyFor(provider, model);

            assertThat(body.path("temperature").asDouble()).isEqualTo(0.7);
            assertThat(body.path("top_p").asDouble()).isEqualTo(0.9);
            assertThat(body.path("frequency_penalty").asDouble()).isEqualTo(0.5);
            assertThat(body.path("presence_penalty").asDouble()).isEqualTo(0.4);
        }

        private JsonNode upstreamBodyFor(LlmProvider provider, String model) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            llmProviderApiKeyResourceClient.createProviderApiKey(ProviderApiKey.builder()
                    .provider(provider)
                    .apiKey("dummy-key")
                    .baseUrl(WIRE_MOCK.runtimeInfo().getHttpBaseUrl())
                    .build(), API_KEY, workspaceName, HttpStatus.SC_CREATED);

            WIRE_MOCK.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                    .willReturn(aResponse()
                            .withStatus(HttpStatus.SC_OK)
                            .withHeader("Content-Type", "application/json")
                            .withBody(OK_BODY)));

            var request = ChatCompletionRequest.builder()
                    .stream(false)
                    .model(model)
                    .temperature(0.7)
                    .topP(0.9)
                    .frequencyPenalty(0.5)
                    .presencePenalty(0.4)
                    .maxCompletionTokens(100)
                    .addUserMessage("ping")
                    .build();

            try (var response = clientSupport
                    .target(TestUtils.getBaseUrl(clientSupport) + "/v1/private/chat/completions")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(request))) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            }

            var upstream = WIRE_MOCK.server().findAll(postRequestedFor(urlPathMatching(CHAT_COMPLETIONS_REGEX)));
            assertThat(upstream).as("Opik should have forwarded the request upstream").isNotEmpty();
            return JsonUtils.getJsonNodeFromString(upstream.getFirst().getBodyAsString());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Provider destinations")
    class ProviderDestinations {

        private static final String MEDIA_URL_REFUSED = """
                a media URL was refused: it must be an http or https URL that resolves to a public address \
                (self-hosted deployments serving media from internal networks can set \
                LLM_PROVIDER_VIDEO_URL_DESTINATION_GUARD=relaxed)""";

        private Stream<Arguments> modelSubstitutedIntoTheAuthorityCannotMoveTheHost() {
            return Stream.of(
                    arguments("https://{model}.gw.example.com/v1", "attacker.example/#"),
                    arguments("https://{model}.gw.example.com/v1", "x@attacker.example"),
                    arguments("https://{model}.gw.example.com/v1", "attacker.example:443"),
                    arguments("https://{model}.gw.example.com/v1", "[::1]"),
                    arguments("https://{model}@gw.example.com/v1", "attacker.example/#"),
                    arguments("https://gw.example.com:{model}/v1", "443@attacker.example"));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("a model substituted into the provider authority can't move the request to another host")
        void modelSubstitutedIntoTheAuthorityCannotMoveTheHost(String baseUrl, String deployment) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            var providerName = RandomStringUtils.randomAlphanumeric(20);
            var model = "custom-llm/%s/%s".formatted(providerName, deployment);
            llmProviderApiKeyResourceClient.createProviderApiKey(ProviderApiKey.builder()
                    .provider(LlmProvider.CUSTOM_LLM)
                    .providerName(providerName)
                    .apiKey("dummy-key")
                    .baseUrl(baseUrl)
                    .configuration(Map.of("provider_name", providerName, "models", model))
                    .build(), API_KEY, workspaceName, HttpStatus.SC_CREATED);
            var expected = new ErrorMessage(HttpStatus.SC_BAD_REQUEST,
                    "model can't be substituted into the provider host or port, only letters, digits, '.' and '-'"
                            + " are allowed");

            var request = ChatCompletionRequest.builder().stream(false).model(model).addUserMessage("ping").build();
            assertThat(chatCompletionsClient.create(API_KEY, workspaceName, request, HttpStatus.SC_BAD_REQUEST))
                    .isEqualTo(expected);

            var streamed = ChatCompletionRequest.builder().stream(true).model(model).addUserMessage("ping").build();
            assertThat(chatCompletionsClient.createAndGetStreamedError(API_KEY, workspaceName, streamed))
                    .containsExactly(expected);
        }

        private Stream<Arguments> vertexAiDestinationsAreRefused() {
            return Stream.of(
                    arguments("attacker.example/#", "https://oauth2.googleapis.com/token",
                            "Vertex AI location is not a valid region"),
                    arguments("us-central1", "https://attacker.example/token",
                            "Vertex AI service account token_uri must be a Google token endpoint"));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("a Vertex AI location or token_uri that could send the request elsewhere is refused")
        void vertexAiDestinationsAreRefused(String location, String tokenUri, String message) throws Exception {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            llmProviderApiKeyResourceClient.createProviderApiKey(ProviderApiKey.builder()
                    .provider(LlmProvider.VERTEX_AI)
                    .apiKey(serviceAccountJson(tokenUri))
                    .configuration(Map.of("location", location))
                    .build(), API_KEY, workspaceName, HttpStatus.SC_CREATED);
            var expected = new ErrorMessage(HttpStatus.SC_BAD_REQUEST, message);
            var model = VertexAIModelName.GEMINI_2_5_FLASH.qualifiedName();

            var request = ChatCompletionRequest.builder().stream(false).model(model).addUserMessage("ping").build();
            assertThat(chatCompletionsClient.create(API_KEY, workspaceName, request, HttpStatus.SC_BAD_REQUEST))
                    .isEqualTo(expected);

            var streamed = ChatCompletionRequest.builder().stream(true).model(model).addUserMessage("ping").build();
            assertThat(chatCompletionsClient.createAndGetStreamedError(API_KEY, workspaceName, streamed))
                    .containsExactly(expected);
        }

        /** A key generated for the test: it never authenticates, the request is refused before any token call. */
        private String serviceAccountJson(String tokenUri) throws Exception {
            var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            var privateKey = Base64.getEncoder()
                    .encodeToString(keyPairGenerator.generateKeyPair().getPrivate().getEncoded());
            return """
                    {"type": "service_account", "project_id": "test-project", "private_key_id": "test-key-id",
                    "private_key": "-----BEGIN PRIVATE KEY-----\\n%s\\n-----END PRIVATE KEY-----\\n",
                    "client_email": "test@test-project.iam.gserviceaccount.com", "client_id": "1234567890",
                    "token_uri": "%s"}"""
                    .formatted(privateKey, tokenUri);
        }

        private Stream<Arguments> mediaUrlOnInternalAddressIsNeverDownloaded() {
            return Stream.of(
                    arguments(LlmProvider.GEMINI, OpikContentType.IMAGE_URL, ".png"),
                    arguments(LlmProvider.GEMINI, OpikContentType.VIDEO_URL, ".mp4"),
                    arguments(LlmProvider.VERTEX_AI, OpikContentType.IMAGE_URL, ".png"),
                    arguments(LlmProvider.VERTEX_AI, OpikContentType.VIDEO_URL, ".mp4"),
                    arguments(LlmProvider.VERTEX_AI, OpikContentType.AUDIO_URL, ".wav"));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("a media URL on an internal address is refused before Gemini or Vertex AI can download it")
        void mediaUrlOnInternalAddressIsNeverDownloaded(LlmProvider provider, OpikContentType type, String extension)
                throws Exception {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            var model = provider == LlmProvider.GEMINI
                    ? GeminiModelName.GEMINI_2_5_FLASH.toString()
                    : VertexAIModelName.GEMINI_2_5_FLASH.qualifiedName();
            llmProviderApiKeyResourceClient.createProviderApiKey(ProviderApiKey.builder()
                    .provider(provider)
                    .apiKey(provider == LlmProvider.GEMINI
                            ? RandomStringUtils.randomAlphanumeric(20)
                            : serviceAccountJson("https://oauth2.googleapis.com/token"))
                    .configuration(provider == LlmProvider.GEMINI ? Map.of() : Map.of("location", "us-central1"))
                    .build(), API_KEY, workspaceName, HttpStatus.SC_CREATED);
            var mediaPath = "/media-%s%s".formatted(UUID.randomUUID(), extension);
            var mediaUrl = WIRE_MOCK.runtimeInfo().getHttpBaseUrl() + mediaPath;
            WIRE_MOCK.server().stubFor(any(urlPathEqualTo(mediaPath))
                    .willReturn(aResponse().withBody(RandomStringUtils.randomAlphanumeric(32))));
            var media = switch (type) {
                case IMAGE_URL -> OpikContent.builder().type(type)
                        .imageUrl(ImageUrl.builder().url(mediaUrl).build()).build();
                case VIDEO_URL -> OpikContent.builder().type(type)
                        .videoUrl(VideoUrl.builder().url(mediaUrl).build()).build();
                case AUDIO_URL -> OpikContent.builder().type(type)
                        .audioUrl(AudioUrl.builder().url(mediaUrl).build()).build();
                case TEXT, AUDIO, FILE -> throw new IllegalArgumentException(type.toString());
            };
            var message = OpikUserMessage.builder()
                    .content(List.of(OpikContent.builder().type(OpikContentType.TEXT).text("describe").build(), media))
                    .build();
            var expectedError = new ErrorMessage(HttpStatus.SC_BAD_REQUEST, MEDIA_URL_REFUSED);

            var request = ChatCompletionRequest.builder().stream(false).model(model).messages(message).build();
            var actualError = chatCompletionsClient.create(API_KEY, workspaceName, request, HttpStatus.SC_BAD_REQUEST);
            assertThat(actualError).isEqualTo(expectedError);

            var streamed = ChatCompletionRequest.builder().stream(true).model(model).messages(message).build();
            var actualStreamedErrors = chatCompletionsClient.createAndGetStreamedError(API_KEY, workspaceName,
                    streamed);
            assertThat(actualStreamedErrors).containsExactly(expectedError);

            WIRE_MOCK.server().verify(0, anyRequestedFor(urlPathEqualTo(mediaPath)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"file:///etc/hosts", "jar:file:///app.jar!/image.png", "ftp://example.com/image.png",
                "example.com/image.png"})
        @DisplayName("a media URL that isn't http or https is refused before Gemini or Vertex AI can read it")
        void mediaUrlWithOtherSchemeIsRefused(String mediaUrl) throws Exception {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            llmProviderApiKeyResourceClient.createProviderApiKey(ProviderApiKey.builder()
                    .provider(LlmProvider.VERTEX_AI)
                    .apiKey(serviceAccountJson("https://oauth2.googleapis.com/token"))
                    .configuration(Map.of("location", "us-central1"))
                    .build(), API_KEY, workspaceName, HttpStatus.SC_CREATED);
            var message = OpikUserMessage.builder()
                    .content(List.of(OpikContent.builder().type(OpikContentType.IMAGE_URL)
                            .imageUrl(ImageUrl.builder().url(mediaUrl).build()).build()))
                    .build();
            var request = ChatCompletionRequest.builder().stream(false)
                    .model(VertexAIModelName.GEMINI_2_5_FLASH.qualifiedName()).messages(message).build();
            var expectedError = new ErrorMessage(HttpStatus.SC_BAD_REQUEST, MEDIA_URL_REFUSED);

            var actualError = chatCompletionsClient.create(API_KEY, workspaceName, request, HttpStatus.SC_BAD_REQUEST);

            assertThat(actualError).isEqualTo(expectedError);
        }

        @Test
        @DisplayName("the OpenAI Responses API client doesn't follow a redirect from the provider")
        void responsesApiDoesNotFollowRedirects() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            var basePath = "/v1-" + UUID.randomUUID();
            var redirectTarget = "/elsewhere-" + UUID.randomUUID();
            WIRE_MOCK.server().stubFor(any(urlPathMatching(basePath + "/.*"))
                    .willReturn(aResponse().withStatus(307).withHeader("Location", redirectTarget)));
            WIRE_MOCK.server().stubFor(any(urlPathEqualTo(redirectTarget)).willReturn(aResponse().withStatus(200)));
            llmProviderApiKeyResourceClient.createProviderApiKey(ProviderApiKey.builder()
                    .provider(LlmProvider.OPEN_AI)
                    .apiKey(RandomStringUtils.randomAlphanumeric(20))
                    .baseUrl(WIRE_MOCK.runtimeInfo().getHttpBaseUrl() + basePath)
                    .configuration(Map.of("openai_pipeline_mode", "responses_api"))
                    .build(), API_KEY, workspaceName, HttpStatus.SC_CREATED);
            var request = ChatCompletionRequest.builder().stream(false)
                    .model(OpenaiModelName.GPT_4O.toString()).addUserMessage("ping").build();

            clientSupport.target(TestUtils.getBaseUrl(clientSupport) + "/v1/private/chat/completions")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(request))
                    .close();

            // the provider was reached, so the redirect was answered and not followed
            var actualProviderRequests = WIRE_MOCK.server().findAll(anyRequestedFor(urlPathMatching(basePath + "/.*")));
            assertThat(actualProviderRequests).isNotEmpty();
            WIRE_MOCK.server().verify(0, anyRequestedFor(urlPathEqualTo(redirectTarget)));
        }

        @Test
        @DisplayName("an extensionless video URL on an internal address is never probed for its MIME type")
        void videoMimeTypeLookupSkipsInternalAddress() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            createLlmProviderApiKey(workspaceName, LlmProvider.GEMINI, "invalid-key");
            var videoPath = "/video-" + UUID.randomUUID();
            WIRE_MOCK.server().stubFor(head(urlPathEqualTo(videoPath))
                    .willReturn(aResponse().withHeader("Content-Type", "video/mp4")));
            var request = JsonUtils.getJsonNodeFromString(
                    """
                            {"model": "%s", "stream": false, "max_completion_tokens": 10, "messages": [{"role": "user",
                            "content": [{"type": "text", "text": "describe"}, {"type": "video_url", "video_url": {"url": "%s"}}]}]}"""
                            .formatted(GeminiModelName.GEMINI_2_5_FLASH,
                                    WIRE_MOCK.runtimeInfo().getHttpBaseUrl() + videoPath));

            // the model call itself fails on the invalid key, the MIME lookup comes before it
            clientSupport.target(TestUtils.getBaseUrl(clientSupport) + "/v1/private/chat/completions")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(request))
                    .close();

            WIRE_MOCK.server().verify(0, headRequestedFor(urlPathEqualTo(videoPath)));
        }
    }

    /// What the provider receives on a key that sends chat completions through the
    /// OpenAI Responses API: a user message's content reaches the provider's input
    /// as text, whether the request sends it as a string or as an array of content
    /// parts. Image parts are rendered the same way as an image placeholder in
    /// string content.
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("OpenAI Responses API user content")
    class OpenAiResponsesApiUserContent {

        private static final String RESPONSES_BODY = """
                {"id":"resp_1","object":"response","created_at":1,"status":"completed","error":null,\
                "incomplete_details":null,"instructions":null,"max_output_tokens":null,"model":"gpt",\
                "parallel_tool_calls":true,"previous_response_id":null,"reasoning":{"effort":null,\
                "summary":null},"store":false,"temperature":1.0,"text":{"format":{"type":"text"}},\
                "tool_choice":"auto","tools":[],"top_p":1.0,"truncation":"disabled","user":null,\
                "metadata":{},"output":[{"type":"message","id":"msg_1","status":"completed",\
                "role":"assistant","content":[{"type":"output_text","text":"ok","annotations":[]}]}],\
                "usage":{"input_tokens":1,"input_tokens_details":{"cached_tokens":0},"output_tokens":1,\
                "output_tokens_details":{"reasoning_tokens":0},"total_tokens":2}}""";

        private Stream<Arguments> userContentReachesTheProviderAsText() {
            var text = RandomStringUtils.randomAlphanumeric(20);
            var imageUrl = "https://example.com/%s.png".formatted(RandomStringUtils.randomAlphanumeric(10));
            var textWithImage = "%s%s%s%s".formatted(text, MessageContentNormalizer.IMAGE_PLACEHOLDER_START, imageUrl,
                    MessageContentNormalizer.IMAGE_PLACEHOLDER_END);
            var textPart = Content.builder().type(ContentType.TEXT).text(text).build();
            var imagePart = Content.builder()
                    .type(ContentType.IMAGE_URL)
                    .imageUrl(ImageUrl.builder().url(imageUrl).build())
                    .build();
            // gpt-4o is vision-capable and gpt-3.5-turbo is not, so each takes its own MessageContentNormalizer path.
            return Stream.of(OpenaiModelName.GPT_4O, OpenaiModelName.GPT_3_5_TURBO)
                    .map(OpenaiModelName::toString)
                    .flatMap(model -> Stream.of(
                            arguments(named("a text part, %s".formatted(model), model),
                                    UserMessage.builder().content(List.of(textPart)).build(), text),
                            arguments(named("a text part and an image part, %s".formatted(model), model),
                                    UserMessage.builder().content(List.of(textPart, imagePart)).build(), textWithImage),
                            arguments(named("string content, %s".formatted(model), model),
                                    UserMessage.builder().content(text).build(), text),
                            arguments(named("string content with an image placeholder, %s".formatted(model), model),
                                    UserMessage.builder().content(textWithImage).build(), textWithImage)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void userContentReachesTheProviderAsText(String model, UserMessage userMessage, String expectedText) {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            mockTargetWorkspace(workspaceName, UUID.randomUUID().toString());
            var basePath = "/v1-%s".formatted(UUID.randomUUID());
            WIRE_MOCK.server().stubFor(post(urlPathEqualTo("%s/responses".formatted(basePath)))
                    .willReturn(okJson(RESPONSES_BODY)));
            llmProviderApiKeyResourceClient.createProviderApiKey(ProviderApiKey.builder()
                    .provider(LlmProvider.OPEN_AI)
                    .apiKey(RandomStringUtils.randomAlphanumeric(20))
                    .baseUrl("%s%s".formatted(WIRE_MOCK.runtimeInfo().getHttpBaseUrl(), basePath))
                    .configuration(Map.of("openai_pipeline_mode", "responses_api"))
                    .build(), API_KEY, workspaceName, HttpStatus.SC_CREATED);
            var request = ChatCompletionRequest.builder()
                    .stream(false)
                    .model(model)
                    .messages(List.<Message>of(userMessage))
                    .build();

            var chatCompletionsUrl = "%s/v1/private/chat/completions".formatted(TestUtils.getBaseUrl(clientSupport));
            try (var actualResponse = clientSupport.target(chatCompletionsUrl)
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(request))) {
                assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_OK);
            }

            var actualProviderRequests = WIRE_MOCK.server().findAll(
                    postRequestedFor(urlPathEqualTo("%s/responses".formatted(basePath))));
            assertThat(actualProviderRequests).hasSize(1);
            var actualInput = JsonUtils.getJsonNodeFromString(actualProviderRequests.getFirst().getBodyAsString())
                    .get("input");
            assertThat(actualInput).isEqualTo(expectedInput(expectedText));
        }

        private JsonNode expectedInput(String expectedText) {
            var expectedPart = JsonUtils.createObjectNode()
                    .put("type", "input_text")
                    .put("text", expectedText);
            var expectedMessage = JsonUtils.createObjectNode()
                    .put("type", "message")
                    .put("role", "user");
            expectedMessage.set("content", JsonUtils.createArrayNode().add(expectedPart));
            return JsonUtils.createArrayNode().add(expectedMessage);
        }
    }
}
