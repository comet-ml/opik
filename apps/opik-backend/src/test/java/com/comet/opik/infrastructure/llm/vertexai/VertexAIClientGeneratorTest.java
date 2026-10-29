package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.llm.ProviderErrorMessageValidator;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.comet.opik.utils.ClientDisconnectedException;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.http.Fault;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.client.Client;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Vertex AI client generator")
class VertexAIClientGeneratorTest {

    private static final String MODEL = "vertex_ai/gemini-2.5-flash";
    private static final String GEMINI_3_MODEL = "vertex_ai/gemini-3-pro-preview";
    private static final String PROJECT_ID = "test-project";

    private static final String GENERATE_CONTENT_PATH = ".*:generateContent";
    private static final String TOKEN_PATH = "/token";

    private static final String GENERATE_CONTENT_RESPONSE = """
            {
              "candidates": [
                {
                  "content": {"role": "model", "parts": [{"text": "hello from the mock"}]},
                  "finishReason": "STOP"
                }
              ],
              "usageMetadata": {"promptTokenCount": 3, "candidatesTokenCount": 4, "totalTokenCount": 7}
            }
            """;

    private static final String TOKEN_RESPONSE = """
            {"access_token": "test-access-token", "token_type": "Bearer", "expires_in": 3600}
            """;

    private final WireMockUtils.WireMockRuntime wireMock = WireMockUtils.startWireMock();

    private final LlmModelRegistryService registry = registryOf(TestConfigUtils.loadConfigTest());

    private String serviceAccountJson;

    /**
     * {@code ServiceAccountCredentials.fromStream} parses and validates the private key, so the fixture needs a real
     * RSA key rather than a placeholder. It is generated per run and its {@code token_uri} points at WireMock, so the
     * OAuth exchange is stubbed too and no real credential exists anywhere in the test.
     */
    @BeforeAll
    void generateServiceAccountKey() throws Exception {
        serviceAccountJson = serviceAccountJson(PROJECT_ID);
    }

    private String serviceAccountJson(String projectId) throws Exception {
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        var privateKey = keyPairGenerator.generateKeyPair().getPrivate();

        var pem = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(privateKey.getEncoded())
                + "\\n-----END PRIVATE KEY-----\\n";

        return """
                {
                  "type": "service_account",
                  "project_id": "%s",
                  "private_key_id": "test-key-id",
                  "private_key": "%s",
                  "client_email": "test@%s.iam.gserviceaccount.com",
                  "client_id": "1234567890",
                  "token_uri": "http://%s%s"
                }
                """.formatted(projectId, pem, projectId, wireMockHost(), TOKEN_PATH);
    }

    @AfterAll
    void tearDown() {
        wireMock.server().stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.server().resetAll();
        wireMock.server().stubFor(post(urlPathMatching(GENERATE_CONTENT_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(GENERATE_CONTENT_RESPONSE)));
        wireMock.server().stubFor(post(urlPathMatching(TOKEN_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(TOKEN_RESPONSE)));
    }

    // Plain HTTP: over TLS the stub's self-signed cert has to be trusted on both the SDK's OkHttp client and the
    // auth library's HttpsURLConnection, and the former is only reachable by injecting a client into production code.
    private String wireMockHost() {
        return "localhost:" + wireMock.server().port();
    }

    /**
     * Starts from the shipped {@code config-test.yml} so the generator is exercised against the block the app boots
     * with, with every multi-region location remapped onto WireMock.
     */
    private LlmProviderClientConfig clientConfig() {
        var endpoint = "http://" + wireMockHost() + "/";
        var config = TestConfigUtils.loadConfigTest().getLlmProviderClient();

        config.setVertexAIClient(config.getVertexAIClient().toBuilder()
                .multiRegionApiEndpoints(Map.of("global", endpoint, "eu", endpoint, "us", endpoint))
                .tokenUris(Set.of(URI.create("http://" + wireMockHost() + TOKEN_PATH)))
                .build());

        return config;
    }

    // config-test.yml leaves the remote layer off, so the registry never calls its HTTP client.
    private static LlmModelRegistryService registryOf(OpikConfiguration configuration) {
        return new LlmModelRegistryService(configuration, mock(Client.class));
    }

    private void completeVia(String configuredLocation) {
        completeVia(new VertexAIClientGenerator(clientConfig(), registry, LlmTestClients.REMOTE_MEDIA_INLINER,
                LlmTestClients.VIDEO_MIME_TYPE_UTILS),
                configuredLocation);
    }

    private void completeVia(VertexAIClientGenerator generator, String configuredLocation) {
        var request = ChatCompletionRequest.builder().model(MODEL).build();
        var config = LlmProviderClientApiConfig.builder()
                .apiKey(serviceAccountJson)
                .configuration(configuredLocation == null ? Map.of() : Map.of("location", configuredLocation))
                .build();

        try (var client = (CloseableVertexAiChatModel) generator.generate(config, request)) {
            client.chat(UserMessage.from("hello"));
        }
    }

    /**
     * 400s, which the SDK doesn't retry, where a 429 would be retried with backoff for over a minute; the mapping
     * doesn't depend on the code.
     */
    private static Stream<Arguments> providerErrorIsPassedOnAsGoogleDocumentsIt() {
        var summary = "Request contains an invalid argument. %s".formatted(RandomStringUtils.secure()
                .nextAlphanumeric(8));
        var message = "%s\n* contents[0].parts: must not be empty".formatted(summary);
        return Stream.of(
                arguments("INVALID_ARGUMENT", message, new ErrorMessage(400, summary, "INVALID_ARGUMENT")),
                arguments("MALFORMED_PROXY_REPLY", message,
                        new ErrorMessage(400, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)));
    }

    /** The SDK raises its own exception, not the JSON envelope; ChatCompletionService validates what is parsed. */
    @ParameterizedTest
    @MethodSource
    @DisplayName("a Vertex AI error keeps its summary line only when Google documents its status")
    void providerErrorIsPassedOnAsGoogleDocumentsIt(String status, String message, ErrorMessage expectedError) {
        wireMock.server().stubFor(post(urlPathMatching(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody(JsonUtils.writeValueAsString(Map.of("error",
                                Map.of("code", 400, "message", message, "status", status))))));
        var generator = new VertexAIClientGenerator(clientConfig(), registry, LlmTestClients.REMOTE_MEDIA_INLINER,
                LlmTestClients.VIDEO_MIME_TYPE_UTILS);
        var apiConfig = LlmProviderClientApiConfig.builder()
                .apiKey(serviceAccountJson)
                .configuration(Map.of("location", "global"))
                .build();
        var validator = new ProviderErrorMessageValidator(clientConfig());

        var failure = catchThrowable(() -> {
            try (var client = (CloseableVertexAiChatModel) generator.generate(apiConfig,
                    ChatCompletionRequest.builder().model(MODEL).build())) {
                client.chat(UserMessage.from("hello"));
            }
        });

        var actualError = new LlmProviderVertexAI(generator, apiConfig).getLlmProviderError(failure)
                .map(validator::validate);
        assertThat(actualError).contains(expectedError);
    }

    private void completeWithCustomParameters(Map<String, Object> customParameters) {
        completeWithCustomParameters(MODEL, customParameters);
    }

    private void completeWithCustomParameters(String model, Map<String, Object> customParameters) {
        var request = ChatCompletionRequest.builder()
                .model(model)
                .customParameters(customParameters)
                .build();
        var config = LlmProviderClientApiConfig.builder()
                .apiKey(serviceAccountJson)
                .configuration(Map.of("location", "global"))
                .build();

        try (var client = (CloseableVertexAiChatModel) new VertexAIClientGenerator(clientConfig(), registry,
                LlmTestClients.REMOTE_MEDIA_INLINER,
                LlmTestClients.VIDEO_MIME_TYPE_UTILS)
                .generate(config, request)) {
            client.chat(UserMessage.from("hello"));
        }
    }

    /**
     * The generation config is not observable on the built client, so it is read back off the request the SDK actually
     * sent.
     */
    private JsonNode sentGenerationConfig() {
        return sentGenerationConfig(GENERATE_CONTENT_PATH);
    }

    private JsonNode sentGenerationConfig(String path) {
        var requests = wireMock.server().findAll(postRequestedFor(urlPathMatching(path)));
        assertThat(requests).hasSize(1);

        // get() rather than path(): path() degrades to a MissingNode, which would make every negative
        // assertion below pass even if the generation config stopped being sent or were renamed.
        var generationConfig = JsonUtils.getJsonNodeFromString(requests.getFirst().getBodyAsString())
                .get("generationConfig");

        assertThat(generationConfig)
                .as("outbound body should carry a generationConfig")
                .isNotNull();

        return generationConfig;
    }

    private void assertCalledWithLocation(String expectedLocation) {
        wireMock.server().verify(postRequestedFor(urlPathMatching(
                ".*/locations/" + expectedLocation + "/publishers/google/models/.*")));
    }

    @Nested
    @DisplayName("Multi-region locations")
    class MultiRegionLocations {

        @ParameterizedTest
        @ValueSource(strings = {"global", "eu", "us"})
        void areCalledOnTheirConfiguredEndpoint(String location) {
            completeVia(location);

            assertCalledWithLocation(location);
        }

        /**
         * The location lands in the resource path as well as the host, so canonicalising it for the endpoint lookup
         * alone would leave the client calling the right host with a malformed {@code locations/} segment.
         */
        @ParameterizedTest
        @CsvSource({"GLOBAL,global", "'  global  ',global", "' EU ',eu"})
        void areCanonicalisedInTheRequestPath(String configured, String expectedLocation) {
            completeVia(configured);

            assertCalledWithLocation(expectedLocation);
        }
    }

    @Nested
    @DisplayName("Locations that keep the SDK-derived endpoint")
    class SdkDerivedEndpoints {

        /**
         * Asserted on the endpoint rather than the location: a configured endpoint wrongly applied to a single-region
         * location misroutes the request while the location still reads correctly. Read back rather than called, since
         * calling these hosts would mean real egress and DNS timeouts in CI.
         */
        @ParameterizedTest
        @ValueSource(strings = {"europe-west4", "us-central1", "asia-northeast1"})
        void areNotRedirectedToTheMultiRegionEndpoint(String location) {
            assertThat(resolvedEndpoint(location))
                    .isEqualTo("https://%s-aiplatform.googleapis.com".formatted(location));
        }

        /** The counterpart to the above, pinning the other side of the lookup. */
        @Test
        @DisplayName("a multi-region location takes its configured endpoint")
        void multiRegionLocationsTakeTheConfiguredEndpoint() {
            assertThat(resolvedEndpoint("global")).isEqualTo("http://" + wireMockHost() + "/");
        }

        /**
         * The configuration accepted bare hosts before the SDK swap and operators may still have them, so one has to
         * keep reaching the same endpoint rather than landing in the URL's path.
         */
        @Test
        @DisplayName("a bare host keeps its scheme defaulted to https")
        void bareHostsAreGivenAScheme() {
            var config = clientConfig();
            config.setVertexAIClient(config.getVertexAIClient().toBuilder()
                    .multiRegionApiEndpoints(Map.of("global", "aiplatform.googleapis.com"))
                    .build());

            assertThat(resolvedEndpoint(config, "global")).isEqualTo("https://aiplatform.googleapis.com");
        }

        /**
         * A blank location is not rejected at the API boundary and the SDK rejects an empty one outright, so it has to
         * be treated as unset. Were it canonicalised into {@code ""}, building the client would fail instead of
         * defaulting like an absent value.
         */
        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void blankLocationsBehaveLikeAnUnsetOne(String location) {
            assertThat(resolvedEndpoint(location)).isEqualTo(resolvedEndpoint(null));
        }

        /** A blank location that reached the builder would surface here as an exception instead of an endpoint. */
        private String resolvedEndpoint(String location) {
            return resolvedEndpoint(clientConfig(), location);
        }

        private String resolvedEndpoint(LlmProviderClientConfig clientConfig, String location) {
            var generator = new VertexAIClientGenerator(clientConfig, registry, LlmTestClients.REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS);
            var request = ChatCompletionRequest.builder().model(MODEL).build();
            var config = LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(location == null ? Map.of() : Map.of("location", location))
                    .build();

            try (var client = (CloseableVertexAiChatModel) generator.generate(config, request)) {
                return VertexAITestClients.apiEndpointOf(client);
            }
        }
    }

    @Nested
    @DisplayName("Call timeout")
    class CallTimeout {

        @Test
        @DisplayName("is passed through to the client")
        void isPassedThroughToTheClient() {
            assertThat(timeoutOfClientWith(Duration.seconds(60))).isEqualTo(60_000);
        }

        /** The SDK takes an int, and the configuration is only bounded below, so an oversized value must not wrap. */
        @Test
        @DisplayName("is clamped instead of overflowing into a negative")
        void isClampedInsteadOfOverflowing() {
            assertThat(timeoutOfClientWith(Duration.days(365))).isEqualTo(Integer.MAX_VALUE);
        }

        private int timeoutOfClientWith(Duration callTimeout) {
            var config = clientConfig();
            config.setCallTimeout(callTimeout);

            var apiConfig = LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(Map.of("location", "global"))
                    .build();
            var request = ChatCompletionRequest.builder().model(MODEL).build();

            try (var client = (CloseableVertexAiChatModel) new VertexAIClientGenerator(config, registry,
                    LlmTestClients.REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS)
                    .generate(apiConfig, request)) {
                return VertexAITestClients.timeoutOf(client);
            }
        }
    }

    @Nested
    @DisplayName("Client ownership")
    class ClientOwnership {

        /**
         * Closing twice must stay safe: the streaming path closes on the stream terminal and callers close the
         * wrapper too.
         */
        @Test
        @DisplayName("closing the returned client is idempotent")
        void closingTheReturnedClientIsIdempotent() {
            var generator = new VertexAIClientGenerator(clientConfig(), registry, LlmTestClients.REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS);
            var request = ChatCompletionRequest.builder().model(MODEL).build();
            var config = LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(Map.of("location", "global"))
                    .build();

            var client = (CloseableVertexAiChatModel) generator.generate(config, request);

            assertThatCode(() -> {
                client.close();
                client.close();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("closing the returned streaming client is idempotent")
        void closingTheReturnedStreamingClientIsIdempotent() {
            var generator = new VertexAIClientGenerator(clientConfig(), registry, LlmTestClients.REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS);
            var request = ChatCompletionRequest.builder().model(MODEL).build();
            var config = LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(Map.of("location", "global"))
                    .build();

            var client = generator.newVertexAIStreamingClient(config, request);

            assertThatCode(() -> {
                client.close();
                client.close();
            }).doesNotThrowAnyException();
        }

        /** A client built for an unresolvable model must not outlive the failure. */
        @Test
        @DisplayName("an unsupported model fails without leaving a client behind")
        void unsupportedModelFailsWithoutLeavingAClientBehind() {
            var generator = new VertexAIClientGenerator(clientConfig(), registry, LlmTestClients.REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS);
            var request = ChatCompletionRequest.builder().model("vertex_ai/not-a-model").build();
            var config = LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(Map.of("location", "global"))
                    .build();

            assertThatThrownBy(() -> generator.generate(config, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported model");
        }
    }

    @Nested
    @DisplayName("Model resolution")
    class ModelResolution {

        @Test
        @DisplayName("a model only the registry knows is called by its bare id")
        void registryOnlyModelIsCalledByItsBareId(@TempDir Path overrideDir) throws Exception {
            var model = "vertex_ai/gemini-9.9-flash";
            assertThat(VertexAIModelName.byQualifiedName(model))
                    .as("the fixture must be a model the enum does not know")
                    .isEmpty();

            var override = Files.writeString(overrideDir.resolve("llm-models-override.yaml"), """
                    vertex-ai:
                      - id: "gemini-9.9-flash"
                        qualifiedName: "%s"
                        label: "Gemini 9.9 Flash"
                        structuredOutput: true
                    """.formatted(model));
            var configuration = TestConfigUtils.loadConfigTest();
            configuration.getLlmModelRegistry().setLocalOverridePath(override.toString());
            var generator = new VertexAIClientGenerator(clientConfig(), registryOf(configuration),
                    LlmTestClients.REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS);
            var request = ChatCompletionRequest.builder().model(model).build();
            var config = LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(Map.of("location", "global"))
                    .build();

            try (var client = (CloseableVertexAiChatModel) generator.generate(config, request)) {
                client.chat(UserMessage.from("hello"));
            }

            wireMock.server().verify(postRequestedFor(urlPathMatching(
                    ".*/publishers/google/models/gemini-9\\.9-flash:generateContent")));
        }

        @Test
        @DisplayName("a model only the enum knows is still called by its bare id")
        void enumOnlyModelIsStillCalledByItsBareId() {
            var configuration = TestConfigUtils.loadConfigTest();
            configuration.getLlmModelRegistry().setDefaultResource("llm-models-test.yaml");
            var registryWithoutTheModel = registryOf(configuration);
            assertThat(registryWithoutTheModel.findModel(MODEL))
                    .as("the fixture registry must not know the model")
                    .isEmpty();

            completeVia(new VertexAIClientGenerator(clientConfig(), registryWithoutTheModel,
                    LlmTestClients.REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS), "global");

            wireMock.server().verify(postRequestedFor(urlPathMatching(
                    ".*/publishers/google/models/gemini-2\\.5-flash:generateContent")));
        }

        @Test
        @DisplayName("a model the registry files under another provider is not sent to Vertex AI")
        void otherProvidersModelIsUnsupported() {
            var generator = new VertexAIClientGenerator(clientConfig(), registry, LlmTestClients.REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS);
            var request = ChatCompletionRequest.builder().model("gemini-2.5-flash").build();
            var config = LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(Map.of("location", "global"))
                    .build();

            assertThatThrownBy(() -> generator.generate(config, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported model");
        }
    }

    @Nested
    @DisplayName("Thinking configuration")
    class ThinkingConfiguration {

        @Test
        @DisplayName("a thinking level is translated into the budget Vertex expects")
        void translatesLevelIntoBudget() {
            completeWithCustomParameters(Map.of("thinking", Map.of("level", "medium")));

            assertThat(sentGenerationConfig().path("thinkingConfig").path("thinkingBudget").asInt())
                    .isEqualTo(8192);
        }

        @Test
        @DisplayName("an explicit budget is forwarded as given")
        void forwardsExplicitBudget() {
            completeWithCustomParameters(Map.of("thinking", Map.of("budget_tokens", 1234)));

            assertThat(sentGenerationConfig().path("thinkingConfig").path("thinkingBudget").asInt())
                    .isEqualTo(1234);
        }

        @Test
        @DisplayName("level off disables thinking, which is the Gemini 2.5 Flash Lite default")
        void levelOffDisablesThinking() {
            completeWithCustomParameters(Map.of("thinking", Map.of("level", "off")));

            // has() before asInt(): a MissingNode also reports 0, so without this the test could not
            // tell "thinkingBudget: 0 was sent" from "no thinkingConfig at all" — the regression it exists
            // to catch.
            var thinkingConfig = sentGenerationConfig().get("thinkingConfig");
            assertThat(thinkingConfig).isNotNull();
            assertThat(thinkingConfig.has("thinkingBudget")).isTrue();
            assertThat(thinkingConfig.get("thinkingBudget").asInt()).isZero();
        }

        @Test
        @DisplayName("no thinking parameters leaves the generation config without a thinking block")
        void omitsThinkingConfigWhenNotRequested() {
            completeWithCustomParameters(Map.of());

            assertThat(sentGenerationConfig().has("thinkingConfig")).isFalse();
        }

        @Test
        @DisplayName("an unrecognised level is ignored rather than guessed at")
        void ignoresUnrecognisedLevel() {
            completeWithCustomParameters(Map.of("thinking", Map.of("level", "aggressive")));

            assertThat(sentGenerationConfig().has("thinkingConfig")).isFalse();
        }

        @Test
        @DisplayName("the judge path forwards thinking from its own custom parameters")
        void judgePathForwardsThinking() {
            var config = LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(Map.of("location", "global"))
                    .build();
            var modelParameters = new LlmAsJudgeModelParameters(MODEL, null, null,
                    JsonUtils.getJsonNodeFromString("{\"thinking\": {\"level\": \"high\"}}"));

            try (var client = (CloseableVertexAiChatModel) new VertexAIClientGenerator(clientConfig(), registry,
                    LlmTestClients.REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS)
                    .generateChat(config, modelParameters)) {
                client.chat(UserMessage.from("hello"));
            }

            assertThat(sentGenerationConfig().path("thinkingConfig").path("thinkingBudget").asInt())
                    .isEqualTo(24576);
        }

        @ParameterizedTest
        @ValueSource(strings = {"minimal", "low", "medium", "high"})
        @DisplayName("Gemini 3 sends the level natively rather than a translated budget")
        void gemini3SendsLevelNatively(String level) {
            completeWithCustomParameters(GEMINI_3_MODEL, Map.of("thinking", Map.of("level", level)));

            var thinkingConfig = sentGenerationConfig().get("thinkingConfig");
            assertThat(thinkingConfig).isNotNull();
            assertThat(thinkingConfig.path("thinkingLevel").asText()).isEqualToIgnoringCase(level);
            // The two fields are mutually exclusive upstream, so the budget must be absent entirely.
            assertThat(thinkingConfig.has("thinkingBudget")).isFalse();
        }

        @Test
        @DisplayName("an explicit budget still wins on Gemini 3, taking the caller at their word")
        void gemini3HonoursExplicitBudget() {
            completeWithCustomParameters(GEMINI_3_MODEL, Map.of("thinking", Map.of("budget_tokens", 4096)));

            var thinkingConfig = sentGenerationConfig().get("thinkingConfig");
            assertThat(thinkingConfig).isNotNull();
            assertThat(thinkingConfig.path("thinkingBudget").asInt()).isEqualTo(4096);
            assertThat(thinkingConfig.has("thinkingLevel")).isFalse();
        }

        @Test
        @DisplayName("level off is dropped on Gemini 3, which cannot disable thinking")
        void gemini3DropsOffLevel() {
            completeWithCustomParameters(GEMINI_3_MODEL, Map.of("thinking", Map.of("level", "off")));

            assertThat(sentGenerationConfig().has("thinkingConfig")).isFalse();
        }

        @Test
        @DisplayName("Gemini 2.5 keeps the budget translation, since it rejects a level outright")
        void gemini25KeepsBudgetTranslation() {
            completeWithCustomParameters(MODEL, Map.of("thinking", Map.of("level", "low")));

            var thinkingConfig = sentGenerationConfig().get("thinkingConfig");
            assertThat(thinkingConfig).isNotNull();
            assertThat(thinkingConfig.path("thinkingBudget").asInt()).isEqualTo(2048);
            assertThat(thinkingConfig.has("thinkingLevel")).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"[1, 2]", "\"x\"", "5", "null"})
        @DisplayName("custom_parameters that is not an object is ignored rather than failing the run")
        void ignoresNonObjectCustomParameters(String customParameters) {
            var config = LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(Map.of("location", "global"))
                    .build();
            var modelParameters = new LlmAsJudgeModelParameters(MODEL, null, null,
                    JsonUtils.getJsonNodeFromString(customParameters));

            try (var client = (CloseableVertexAiChatModel) new VertexAIClientGenerator(clientConfig(), registry,
                    LlmTestClients.REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS)
                    .generateChat(config, modelParameters)) {
                client.chat(UserMessage.from("hello"));
            }

            assertThat(sentGenerationConfig().has("thinkingConfig")).isFalse();
        }
    }

    @Nested
    @DisplayName("Thought signatures")
    class ThoughtSignatures {

        @Test
        @DisplayName("the judge's next tool-loop round sends back the thought signature of each function call")
        void judgeSendsBackTheThoughtSignature() {
            var signature = Base64.getEncoder()
                    .encodeToString(RandomStringUtils.secure().nextAlphanumeric(12).getBytes(StandardCharsets.UTF_8));
            wireMock.server().stubFor(post(urlPathMatching(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                    """
                                            {"candidates": [{"content": {"role": "model", "parts": [\
                                            {"functionCall": {"name": "read", "args": {"id": "t1"}}, "thoughtSignature": "%s"}]}, \
                                            "finishReason": "STOP"}], \
                                            "usageMetadata": {"promptTokenCount": 3, "candidatesTokenCount": 4, "totalTokenCount": 7}}"""
                                            .formatted(signature))));
            var config = LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(Map.of("location", "global"))
                    .build();
            var question = UserMessage.from("Read trace t1");

            try (var client = (CloseableVertexAiChatModel) new VertexAIClientGenerator(clientConfig(), registry,
                    LlmTestClients.REMOTE_MEDIA_INLINER, LlmTestClients.VIDEO_MIME_TYPE_UTILS)
                    .generateChat(config, new LlmAsJudgeModelParameters(GEMINI_3_MODEL, null, null, null))) {
                var call = client.chat(ChatRequest.builder().messages(question).build()).aiMessage();
                client.chat(ChatRequest.builder()
                        .messages(question, call,
                                ToolExecutionResultMessage.from(call.toolExecutionRequests().getFirst(), "banana"))
                        .build());
            }

            var requests = wireMock.server().findAll(postRequestedFor(urlPathMatching(GENERATE_CONTENT_PATH)));
            assertThat(requests).hasSize(2);
            var sentCalls = JsonUtils.getJsonNodeFromString(requests.get(1).getBodyAsString())
                    .findParents("functionCall");
            assertThat(sentCalls).hasSize(1);
            assertThat(sentCalls.getFirst().path("thoughtSignature").asText()).isEqualTo(signature);
        }
    }

    @Nested
    @DisplayName("Temperature and top_p")
    class SamplingParams {

        private static final String STREAM_GENERATE_CONTENT_PATH = ".*:streamGenerateContent";

        private static Stream<Arguments> samplingCases() {
            return Stream.of(
                    Arguments.of("both as set", 0.3, 0.8, "{\"temperature\": 0.3, \"topP\": 0.8}"),
                    Arguments.of("a temperature of 0 is still sent", 0.0, null, "{\"temperature\": 0.0}"),
                    Arguments.of("neither set sends neither", null, null, "{}"));
        }

        @ParameterizedTest(name = "chat: {0}")
        @MethodSource("samplingCases")
        void chatSendsThePanelsSamplingParams(String name, Double temperature, Double topP, String expected) {
            provider().generate(request(temperature, topP), "workspace");

            assertThat(only(sentGenerationConfig(), "temperature", "topP"))
                    .isEqualTo(JsonUtils.getJsonNodeFromString(expected));
        }

        @ParameterizedTest(name = "streaming: {0}")
        @MethodSource("samplingCases")
        void streamingSendsThePanelsSamplingParams(String name, Double temperature, Double topP, String expected)
                throws Exception {
            wireMock.server().stubFor(post(urlPathMatching(STREAM_GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "text/event-stream")
                            .withBody("data: " + GENERATE_CONTENT_RESPONSE.replace("\n", " ") + "\n\n")));
            var closed = new CompletableFuture<Void>();

            provider().generateStream(request(temperature, topP), "workspace", message -> {
            }, () -> closed.complete(null), closed::completeExceptionally);
            closed.get(10, TimeUnit.SECONDS);

            assertThat(only(sentGenerationConfig(STREAM_GENERATE_CONTENT_PATH), "temperature", "topP"))
                    .isEqualTo(JsonUtils.getJsonNodeFromString(expected));
        }

        @Test
        @DisplayName("the judge path sends the rule's temperature and seed")
        void judgeSendsTemperatureAndSeed() {
            var modelParameters = new LlmAsJudgeModelParameters(MODEL, 0.2, 7, null);

            try (var client = (CloseableVertexAiChatModel) new VertexAIClientGenerator(clientConfig(), registry,
                    LlmTestClients.REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS)
                    .generateChat(apiConfig(), modelParameters)) {
                client.chat(UserMessage.from("hello"));
            }

            assertThat(only(sentGenerationConfig(), "temperature", "seed"))
                    .isEqualTo(JsonUtils.getJsonNodeFromString("{\"temperature\": 0.2, \"seed\": 7}"));
        }

        private ChatCompletionRequest request(Double temperature, Double topP) {
            return ChatCompletionRequest.builder()
                    .model(MODEL)
                    .addUserMessage("hello")
                    .temperature(temperature)
                    .topP(topP)
                    .build();
        }

        private LlmProviderVertexAI provider() {
            return new LlmProviderVertexAI(
                    new VertexAIClientGenerator(clientConfig(), registry, LlmTestClients.REMOTE_MEDIA_INLINER,
                            LlmTestClients.VIDEO_MIME_TYPE_UTILS),
                    apiConfig());
        }

        private LlmProviderClientApiConfig apiConfig() {
            return LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(Map.of("location", "global"))
                    .build();
        }

        private JsonNode only(JsonNode generationConfig, String... fields) {
            var subset = JsonUtils.createObjectNode();
            for (var field : fields) {
                if (generationConfig.has(field)) {
                    subset.set(field, generationConfig.get(field));
                }
            }
            return subset;
        }
    }

    @Nested
    @DisplayName("Max output tokens")
    class MaxOutputTokens {

        private static final String STREAM_GENERATE_CONTENT_PATH = ".*:streamGenerateContent";

        private static final String EXPECTED_CUT_OFF_MESSAGE = "Vertex AI used up the max output tokens limit (1024) "
                + "before writing any answer. Thinking tokens count toward this limit, so raise Max output tokens "
                + "and run again";

        private static final String EXPECTED_CUT_OFF_MESSAGE_WITHOUT_A_LIMIT = "Vertex AI used up the max output "
                + "tokens limit before writing any answer. Thinking tokens count toward this limit, so raise Max "
                + "output tokens and run again";

        private static final String CUT_OFF_BEFORE_ANY_TEXT = """
                {
                  "candidates": [{"content": {"role": "model"}, "finishReason": "MAX_TOKENS"}],
                  "usageMetadata": {"promptTokenCount": 3, "thoughtsTokenCount": 1024, "totalTokenCount": 1027}
                }
                """;

        private static final String CUT_OFF_MID_ANSWER = """
                {
                  "candidates": [
                    {
                      "content": {"role": "model", "parts": [{"text": "The answer is"}]},
                      "finishReason": "MAX_TOKENS"
                    }
                  ],
                  "usageMetadata": {"promptTokenCount": 3, "candidatesTokenCount": 3, "totalTokenCount": 6}
                }
                """;

        private static Stream<Arguments> maxOutputTokensCases() {
            return Stream.of(
                    Arguments.of("only max_completion_tokens", 2048, null, 2048),
                    Arguments.of("only max_tokens", null, 512, 512),
                    Arguments.of("both, max_completion_tokens wins", 2048, 512, 2048),
                    Arguments.of("max_completion_tokens 0 falls back to max_tokens", 0, 512, 512),
                    Arguments.of("max_completion_tokens 0", 0, null, null),
                    Arguments.of("max_tokens 0", null, 0, null),
                    Arguments.of("negative max_completion_tokens", -1, null, null),
                    Arguments.of("neither", null, null, null));
        }

        @ParameterizedTest(name = "chat: {0}")
        @MethodSource("maxOutputTokensCases")
        void chatClientSendsTheResolvedCap(String name, Integer maxCompletionTokens, Integer maxTokens,
                Integer expected) {
            var request = request(MODEL, maxCompletionTokens, maxTokens);

            try (var client = new VertexAIClientGenerator(clientConfig(), registry, LlmTestClients.REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS)
                    .newVertexAIClient(apiConfig(),
                            request)) {
                client.chat(UserMessage.from("hello"));
            }

            assertSentMaxOutputTokens(sentGenerationConfig(), expected);
        }

        @ParameterizedTest(name = "streaming: {0}")
        @MethodSource("maxOutputTokensCases")
        void streamingClientSendsTheResolvedCap(String name, Integer maxCompletionTokens, Integer maxTokens,
                Integer expected) throws Exception {
            stubStream(GENERATE_CONTENT_RESPONSE);
            var request = request(MODEL, maxCompletionTokens, maxTokens);
            var completed = new CompletableFuture<ChatResponse>();

            try (var client = new VertexAIClientGenerator(clientConfig(), registry, LlmTestClients.REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS)
                    .newVertexAIStreamingClient(apiConfig(), request)) {
                client.chat(List.of(UserMessage.from("hello")), new StreamingChatResponseHandler() {
                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        completed.complete(response);
                    }

                    @Override
                    public void onError(Throwable error) {
                        completed.completeExceptionally(error);
                    }
                });
                completed.get(10, TimeUnit.SECONDS);
            }

            assertSentMaxOutputTokens(sentGenerationConfig(STREAM_GENERATE_CONTENT_PATH), expected);
        }

        private static Stream<Arguments> cutOffMessages() {
            return Stream.of(
                    Arguments.of(1024, EXPECTED_CUT_OFF_MESSAGE),
                    Arguments.of(null, EXPECTED_CUT_OFF_MESSAGE_WITHOUT_A_LIMIT));
        }

        @ParameterizedTest(name = "max_completion_tokens {0}")
        @MethodSource("cutOffMessages")
        @DisplayName("a reply cut off before any text fails instead of returning an empty answer")
        void cutOffBeforeAnyTextFails(Integer maxCompletionTokens, String expectedMessage) {
            stubGenerate(CUT_OFF_BEFORE_ANY_TEXT);
            var request = request(GEMINI_3_MODEL, maxCompletionTokens, null);

            assertThatThrownBy(() -> provider().generate(request, "workspace"))
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessage(expectedMessage);
        }

        @Test
        @DisplayName("a streamed reply cut off before any text ends the stream with an error")
        void streamedCutOffBeforeAnyTextFails() throws Exception {
            stubStream(CUT_OFF_BEFORE_ANY_TEXT);

            var outcome = stream(request(GEMINI_3_MODEL, 1024, null));

            assertThat(outcome.error())
                    .isInstanceOf(InvalidRequestException.class)
                    .hasMessage(EXPECTED_CUT_OFF_MESSAGE);
            assertThat(outcome.closed()).isFalse();
            assertThat(outcome.content()).isEmpty();
        }

        @Test
        @DisplayName("a reply cut off mid-answer keeps the partial text, like any other provider")
        void cutOffMidAnswerKeepsThePartialText() {
            stubGenerate(CUT_OFF_MID_ANSWER);

            var response = provider().generate(request(GEMINI_3_MODEL, 1024, null), "workspace");

            assertThat(response.choices().getFirst().message().content()).isEqualTo("The answer is");
        }

        @Test
        @DisplayName("a streamed reply cut off mid-answer keeps the partial text and closes normally")
        void streamedCutOffMidAnswerKeepsThePartialText() throws Exception {
            stubStream(CUT_OFF_MID_ANSWER);

            var outcome = stream(request(GEMINI_3_MODEL, 1024, null));

            assertThat(outcome.error()).isNull();
            assertThat(outcome.closed()).isTrue();
            assertThat(outcome.content()).isEqualTo("The answer is");
        }

        private static Stream<Method> streamingCallbacks() {
            return Arrays.stream(StreamingChatResponseHandler.class.getMethods());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("streamingCallbacks")
        @DisplayName("the cut-off check passes every streaming callback on to the wrapped handler")
        void cutOffCheckForwardsEveryStreamingCallback(Method callback) throws Exception {
            var delegate = mock(StreamingChatResponseHandler.class);
            var handler = LlmProviderVertexAI.failingWhenCutOffBeforeAnswering(request(MODEL, null, null), delegate);
            var arguments = Arrays.stream(callback.getParameterTypes())
                    .map(type -> type == String.class ? "text" : mock(type))
                    .toArray();

            callback.invoke(handler, arguments);

            assertThat(mockingDetails(delegate).getInvocations()).singleElement().satisfies(invocation -> {
                assertThat(invocation.getMethod()).isEqualTo(callback);
                assertThat(invocation.getArguments()).containsExactly(arguments);
            });
        }

        private record StreamOutcome(String content, boolean closed, Throwable error) {
        }

        private StreamOutcome stream(ChatCompletionRequest request) throws Exception {
            var content = new StringBuilder();
            var closed = new AtomicBoolean();
            var error = new AtomicReference<Throwable>();
            var terminal = new CountDownLatch(1);

            provider().generateStream(request, "workspace",
                    message -> message.choices().stream()
                            .map(choice -> choice.delta().content())
                            .filter(Objects::nonNull)
                            .forEach(content::append),
                    () -> {
                        closed.set(true);
                        terminal.countDown();
                    },
                    throwable -> {
                        error.set(throwable);
                        terminal.countDown();
                    });

            assertThat(terminal.await(10, TimeUnit.SECONDS)).isTrue();
            return new StreamOutcome(content.toString(), closed.get(), error.get());
        }

        private LlmProviderVertexAI provider() {
            return new LlmProviderVertexAI(
                    new VertexAIClientGenerator(clientConfig(), registry, LlmTestClients.REMOTE_MEDIA_INLINER,
                            LlmTestClients.VIDEO_MIME_TYPE_UTILS),
                    apiConfig());
        }

        private LlmProviderClientApiConfig apiConfig() {
            return LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(Map.of("location", "global"))
                    .build();
        }

        private ChatCompletionRequest request(String model, Integer maxCompletionTokens, Integer maxTokens) {
            return ChatCompletionRequest.builder()
                    .model(model)
                    .addUserMessage("hello")
                    .maxCompletionTokens(maxCompletionTokens)
                    .maxTokens(maxTokens)
                    .build();
        }

        private void stubGenerate(String body) {
            wireMock.server().stubFor(post(urlPathMatching(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(body)));
        }

        // The SDK streams over SSE, one JSON object per data line, so the multi-line fixture is flattened.
        private void stubStream(String body) {
            wireMock.server().stubFor(post(urlPathMatching(STREAM_GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "text/event-stream")
                            .withBody("data: " + body.replace("\n", " ") + "\n\n")));
        }

        // has() rather than asInt(): a MissingNode reads as 0, which would hide a cap of 0 being sent.
        private void assertSentMaxOutputTokens(JsonNode generationConfig, Integer expected) {
            if (expected == null) {
                assertThat(generationConfig.has("maxOutputTokens")).isFalse();
                return;
            }
            assertThat(generationConfig.has("maxOutputTokens")).isTrue();
            assertThat(generationConfig.get("maxOutputTokens").asInt()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("Resending")
    class Resending {

        private static final String STREAM_GENERATE_CONTENT_PATH = ".*:streamGenerateContent";

        /**
         * The SDK's own {@code RetryInterceptor} tries a call up to 5 times when it fails with an IOException, as Opik
         * leaves its retry options unset, waiting up to 30 seconds in all, within the 60 second call timeout. Each of
         * those tries reaches the provider once: OkHttp sends none of them again, as it would the first, on the
         * connection the warm-up call pooled. The streamed call is used as the chat one also retries in LangChain4j.
         */
        private static final int EXPECTED_SDK_ATTEMPTS = 5;

        private LlmProviderClientApiConfig apiConfig() {
            return LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(Map.of("location", "global"))
                    .build();
        }

        private ChatCompletionRequest request() {
            return ChatCompletionRequest.builder().model(MODEL).build();
        }

        /**
         * A provider that closes the connection after taking the request may have acted on it, so OkHttp reports the
         * failure to the SDK rather than sending the request again, even on a pooled connection, which it would
         * otherwise take for a stale one.
         */
        @ParameterizedTest
        @ValueSource(strings = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
        @DisplayName("a request whose sending started is never sent again by OkHttp")
        void aRequestWhoseSendingStartedIsNeverSentAgain(Fault fault) throws Exception {
            wireMock.server().stubFor(post(urlPathMatching(STREAM_GENERATE_CONTENT_PATH))
                    .willReturn(aResponse().withFault(fault)));
            var generator = new VertexAIClientGenerator(clientConfig(), registry,
                    LlmTestClients.REMOTE_MEDIA_INLINER, LlmTestClients.VIDEO_MIME_TYPE_UTILS);
            try (var warmUp = (CloseableVertexAiChatModel) generator.generate(apiConfig(), request())) {
                warmUp.chat(UserMessage.from("hello"));
            }
            var failed = new CompletableFuture<Throwable>();

            try (var client = generator.newVertexAIStreamingClient(apiConfig(), request())) {
                client.chat(List.of(UserMessage.from("hello")), new StreamingChatResponseHandler() {
                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        failed.complete(null);
                    }

                    @Override
                    public void onError(Throwable error) {
                        failed.complete(error);
                    }
                });
                assertThat(failed.get(45, TimeUnit.SECONDS)).isNotNull();
            }

            wireMock.server().verify(EXPECTED_SDK_ATTEMPTS, postRequestedFor(urlPathMatching(
                    STREAM_GENERATE_CONTENT_PATH)));
        }

        /**
         * Connecting to the provider through the proxy, its first route, is refused, and OkHttp fails over to the next
         * one, the direct route, within the SDK's first try: the routes are selected once per try, so once in all.
         */
        @Test
        @DisplayName("a connect failure fails over to the next route")
        void aConnectFailureFailsOverToTheNextRoute() throws IOException {
            var expectedText = RandomStringUtils.secure().nextAlphanumeric(20);
            wireMock.server().stubFor(post(urlPathMatching(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(GENERATE_CONTENT_RESPONSE.replace("hello from the mock", expectedText))));
            var routes = new RefusingProxyFirst(wireMock.server().port(), refusingProxy());
            var generator = generatorBehind(routes);

            String actualText;
            try (var client = (CloseableVertexAiChatModel) generator.generate(apiConfig(), request())) {
                actualText = client.chat("hello");
            }

            assertThat(actualText).isEqualTo(expectedText);
            assertThat(routes.selections).hasValue(1);
            wireMock.server().verify(1, postRequestedFor(urlPathMatching(GENERATE_CONTENT_PATH)));
        }

        /** An HTTP proxy on a loopback port nothing listens on, so connecting to it is refused at once. */
        private static Proxy refusingProxy() throws IOException {
            try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                return new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), socket.getLocalPort()));
            }
        }

        /**
         * A generator whose clients route through {@code proxySelector}. OkHttp takes the system default at client
         * construction, which the generator does once, so the default is swapped back right after.
         */
        private VertexAIClientGenerator generatorBehind(ProxySelector proxySelector) {
            var systemDefault = ProxySelector.getDefault();
            ProxySelector.setDefault(proxySelector);
            try {
                return new VertexAIClientGenerator(clientConfig(), registry, LlmTestClients.REMOTE_MEDIA_INLINER,
                        LlmTestClients.VIDEO_MIME_TYPE_UTILS);
            } finally {
                ProxySelector.setDefault(systemDefault);
            }
        }
    }

    /** Routes calls to the provider on {@code port} through {@code proxy} first, then directly, counting selections. */
    @RequiredArgsConstructor
    private static final class RefusingProxyFirst extends ProxySelector {

        private final int port;
        private final Proxy proxy;
        private final AtomicInteger selections = new AtomicInteger();

        @Override
        public List<Proxy> select(URI uri) {
            if (uri.getPort() != port) {
                return List.of(Proxy.NO_PROXY);
            }
            selections.incrementAndGet();
            return List.of(proxy, Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException exception) {
            // OkHttp tracks failed routes itself
        }
    }

    /** The pool and dispatcher a client runs on. */
    @Builder(toBuilder = true)
    private record Transport(ConnectionPool connectionPool, Dispatcher dispatcher) {
        static Transport of(OkHttpClient client) {
            return Transport.builder().connectionPool(client.connectionPool()).dispatcher(client.dispatcher()).build();
        }
    }

    /** Every call, streamed or not, runs on the one connection pool and dispatcher, rather than on its own. */
    @Test
    @DisplayName("Vertex AI calls share one connection pool and dispatcher")
    void callsShareOneTransport() {
        var generator = new VertexAIClientGenerator(clientConfig(), registry, LlmTestClients.REMOTE_MEDIA_INLINER,
                LlmTestClients.VIDEO_MIME_TYPE_UTILS);
        var config = LlmProviderClientApiConfig.builder()
                .apiKey(serviceAccountJson)
                .configuration(Map.of("location", "global"))
                .build();
        var request = ChatCompletionRequest.builder().model(MODEL).build();

        try (var first = (CloseableVertexAiChatModel) generator.generate(config, request);
                var second = generator.newVertexAIStreamingClient(config, request)) {
            var expectedTransport = Transport.of(VertexAITestClients.httpClientOf(first.client()));

            var actualTransport = Transport.of(VertexAITestClients.httpClientOf(second.client()));

            assertThat(actualTransport.connectionPool()).isSameAs(expectedTransport.connectionPool());
            assertThat(actualTransport.dispatcher()).isSameAs(expectedTransport.dispatcher());
        }
    }

    /** Calls made per kind, chat and streamed, by {@link #callsLeaveNoThreadsBehind}. */
    private static final int CALLS = 50;

    /**
     * The shared, bounded pools a round of calls may grow: the virtual-thread and common fork-join pools, Reactor's
     * bounded elastic scheduler the stream starts on, OkHttp's one task runner and the shared dispatcher, and
     * WireMock's own Jetty pool. Anything else started by the calls is theirs alone, and must end with them.
     */
    private static final List<String> SHARED_POOL_PREFIXES = List.of(
            "ForkJoinPool", "boundedElastic", "OkHttp TaskRunner", "OkHttp Dispatcher", "qtp");

    private static Set<Long> liveThreadIds() {
        return Thread.getAllStackTraces().keySet().stream().map(Thread::threadId).collect(Collectors.toSet());
    }

    /**
     * Calls, streamed or not, leave no thread of their own behind: a client per call runs on the shared transport,
     * and the SDK starts no executor on these paths. The threads alive after a first round, which brings the shared
     * pools up, are left out; of those a second round starts, only the shared pools' may still be alive once it
     * settled.
     */
    @Test
    @DisplayName("Vertex AI calls, streamed or not, leave no thread behind")
    void callsLeaveNoThreadsBehind() {
        wireMock.server().stubFor(post(urlPathMatching(".*:streamGenerateContent"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: %s\n\n".formatted(GENERATE_CONTENT_RESPONSE.replace("\n", " ")))));
        var provider = new LlmProviderVertexAI(new VertexAIClientGenerator(clientConfig(), registry,
                LlmTestClients.REMOTE_MEDIA_INLINER, LlmTestClients.VIDEO_MIME_TYPE_UTILS),
                LlmProviderClientApiConfig.builder()
                        .apiKey(serviceAccountJson)
                        .configuration(Map.of("location", "global"))
                        .build());
        var request = ChatCompletionRequest.builder().model(MODEL).addUserMessage("hello").build();
        Runnable round = () -> {
            for (int i = 0; i < CALLS; i++) {
                provider.generate(request, "workspace");
                var ended = new CompletableFuture<Void>();
                provider.generateStream(request, "workspace", message -> {
                }, () -> ended.complete(null), ended::completeExceptionally);
                assertThat(ended).succeedsWithin(10, TimeUnit.SECONDS);
            }
        };
        round.run();
        var threadsBefore = liveThreadIds();

        round.run();

        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                // polled on this thread, so that the wait starts none of its own
                .pollInSameThread()
                .untilAsserted(() -> assertThat(Thread.getAllStackTraces().keySet())
                        .filteredOn(thread -> !threadsBefore.contains(thread.threadId()))
                        .extracting(Thread::getName)
                        .allMatch(name -> SHARED_POOL_PREFIXES.stream().anyMatch(name::startsWith)));
    }

    /**
     * A raw server answering one streamed call with two events, then holding it open until the client lets go. Two,
     * as the SDK reads one event ahead: the first reaches the caller once the second arrived.
     */
    private static ServerSocket streamHeldOpen(CompletableFuture<Void> providerClosed) throws IOException {
        var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (var socket = server.accept(); var out = socket.getOutputStream()) {
                var in = socket.getInputStream();
                var head = new StringBuilder();
                while (!head.toString().endsWith("\r\n\r\n")) {
                    head.append((char) in.read());
                }
                out.write("""
                        HTTP/1.1 200 OK\r
                        Content-Type: text/event-stream\r
                        Connection: close\r
                        \r
                        data: {"candidates": [{"content": {"role": "model", "parts": [{"text": "hi"}]}}]}\r
                        \r
                        data: {"candidates": [{"content": {"role": "model", "parts": [{"text": "there"}]}}]}\r
                        \r
                        """.getBytes(StandardCharsets.UTF_8));
                out.flush();
                // the request body, then nothing until the client closes the connection
                while (in.read() != -1) {
                    // nothing more is expected
                }
                providerClosed.complete(null);
            } catch (IOException exception) {
                // a reset is the client letting go too
                providerClosed.complete(null);
            }
        });
        return server;
    }

    /** A caller gone after the first chunk ends the Vertex AI call, rather than the stream being read to its end. */
    @Test
    @DisplayName("a disconnected caller cancels the Vertex AI stream")
    void aDisconnectedCallerCancelsTheStream() throws Exception {
        var providerClosed = new CompletableFuture<Void>();
        try (var server = streamHeldOpen(providerClosed)) {
            var endpoint = "http://localhost:%d/".formatted(server.getLocalPort());
            var config = clientConfig();
            config.setVertexAIClient(config.getVertexAIClient().toBuilder()
                    .multiRegionApiEndpoints(Map.of("global", endpoint))
                    .build());
            var provider = new LlmProviderVertexAI(new VertexAIClientGenerator(config, registry,
                    LlmTestClients.REMOTE_MEDIA_INLINER, LlmTestClients.VIDEO_MIME_TYPE_UTILS),
                    LlmProviderClientApiConfig.builder()
                            .apiKey(serviceAccountJson)
                            .configuration(Map.of("location", "global"))
                            .build());

            provider.generateStream(ChatCompletionRequest.builder().model(MODEL).addUserMessage("hello").build(),
                    "workspace", message -> {
                        throw new ClientDisconnectedException(new IOException("closed"));
                    }, () -> {
                    }, error -> {
                    });

            assertThat(providerClosed).succeedsWithin(10, TimeUnit.SECONDS);
        }
    }
}
