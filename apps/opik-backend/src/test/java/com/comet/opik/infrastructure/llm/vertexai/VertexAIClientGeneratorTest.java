package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .build());

        return config;
    }

    private void completeVia(String configuredLocation) {
        completeVia(new VertexAIClientGenerator(clientConfig()), configuredLocation);
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

        try (var client = (CloseableVertexAiChatModel) new VertexAIClientGenerator(clientConfig())
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
            var generator = new VertexAIClientGenerator(clientConfig);
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

            try (var client = (CloseableVertexAiChatModel) new VertexAIClientGenerator(config)
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
            var generator = new VertexAIClientGenerator(clientConfig());
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
            var generator = new VertexAIClientGenerator(clientConfig());
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
            var generator = new VertexAIClientGenerator(clientConfig());
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

            try (var client = (CloseableVertexAiChatModel) new VertexAIClientGenerator(clientConfig())
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

            try (var client = (CloseableVertexAiChatModel) new VertexAIClientGenerator(clientConfig())
                    .generateChat(config, modelParameters)) {
                client.chat(UserMessage.from("hello"));
            }

            assertThat(sentGenerationConfig().has("thinkingConfig")).isFalse();
        }
    }

    @Nested
    @DisplayName("Max output tokens")
    class MaxOutputTokens {

        private static final String STREAM_GENERATE_CONTENT_PATH = ".*:streamGenerateContent";

        private static final String EXPECTED_CUT_OFF_MESSAGE = "Vertex AI used up the max output tokens limit "
                + "before writing any answer. Thinking tokens count toward this limit, so raise Max output tokens "
                + "and run again";

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

            try (var client = new VertexAIClientGenerator(clientConfig()).newVertexAIClient(apiConfig(), request)) {
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

            try (var client = new VertexAIClientGenerator(clientConfig())
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
                    Arguments.of(1024, EXPECTED_CUT_OFF_MESSAGE + ", max output tokens '1024'"),
                    Arguments.of(null, EXPECTED_CUT_OFF_MESSAGE));
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
                    .hasMessage(EXPECTED_CUT_OFF_MESSAGE + ", max output tokens '1024'");
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
            return new LlmProviderVertexAI(new VertexAIClientGenerator(clientConfig()), apiConfig());
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
}
