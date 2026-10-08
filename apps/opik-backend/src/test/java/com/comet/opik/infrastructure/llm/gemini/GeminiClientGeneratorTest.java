package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Gemini (Google AI Studio) client generator: what reaches Google")
class GeminiClientGeneratorTest {

    private static final String GEMINI_2_5_MODEL = "gemini-2.5-flash";
    private static final String GEMINI_3_MODEL = "gemini-3-flash-preview";

    private static final String GENERATE_CONTENT_PATH = ".*:generateContent";
    private static final String STREAM_GENERATE_CONTENT_PATH = ".*:streamGenerateContent";

    private static final String GENERATE_CONTENT_RESPONSE = """
            {"candidates": [{"content": {"role": "model", "parts": [{"text": "hello from the mock"}]}, \
            "finishReason": "STOP"}], \
            "usageMetadata": {"promptTokenCount": 3, "candidatesTokenCount": 4, "totalTokenCount": 7}}""";

    private final WireMockUtils.WireMockRuntime wireMock = WireMockUtils.startWireMock();

    private GeminiClientGenerator generator;

    enum Client {
        CHAT,
        STREAMING
    }

    @BeforeAll
    void setUpAll() {
        var clientConfig = new LlmProviderClientConfig();
        clientConfig.setCallTimeout(Duration.seconds(10));
        clientConfig.setLogRequests(false);
        clientConfig.setLogResponses(false);
        generator = new GeminiClientGenerator(clientConfig);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.server().resetAll();
        wireMock.server().stubFor(post(urlPathMatching(GENERATE_CONTENT_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(GENERATE_CONTENT_RESPONSE)));
        wireMock.server().stubFor(post(urlPathMatching(STREAM_GENERATE_CONTENT_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: " + GENERATE_CONTENT_RESPONSE + "\n\n")));
    }

    static Stream<Arguments> samplingCases() {
        return Stream.of(Client.values()).flatMap(client -> Stream.of(
                arguments(client, "both as set", 0.3, 0.8, "{\"temperature\": 0.3, \"topP\": 0.8}"),
                arguments(client, "a temperature of 0 is still sent", 0.0, null, "{\"temperature\": 0.0}"),
                arguments(client, "neither set sends neither", null, null, "{}")));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("samplingCases")
    @DisplayName("playground: the panel's temperature and top_p")
    void playgroundSendsTheSamplingParams(Client client, String name, Double temperature, Double topP,
            String expected) throws Exception {
        var request = ChatCompletionRequest.builder()
                .model(GEMINI_2_5_MODEL)
                .addUserMessage("hello")
                .temperature(temperature)
                .topP(topP)
                .build();

        var generationConfig = send(client, request);

        assertThat(only(generationConfig, "temperature", "topP"))
                .isEqualTo(JsonUtils.getJsonNodeFromString(expected));
    }

    static Stream<Arguments> maxOutputTokensCases() {
        return Stream.of(Client.values()).flatMap(client -> Stream.of(
                arguments(client, "only max_completion_tokens", 2048, null, 2048),
                arguments(client, "only max_tokens", null, 512, 512),
                arguments(client, "both, max_completion_tokens wins", 2048, 512, 2048),
                arguments(client, "max_completion_tokens 0 falls back to max_tokens", 0, 512, 512),
                arguments(client, "max_completion_tokens 0, the slider's minimum", 0, null, null),
                arguments(client, "max_tokens 0", null, 0, null),
                arguments(client, "neither", null, null, null)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("maxOutputTokensCases")
    @DisplayName("playground: the max output tokens cap, never 0, the same rule as Vertex AI")
    void playgroundSendsTheResolvedCap(Client client, String name, Integer maxCompletionTokens, Integer maxTokens,
            Integer expected) throws Exception {
        var request = ChatCompletionRequest.builder()
                .model(GEMINI_2_5_MODEL)
                .addUserMessage("hello")
                .maxCompletionTokens(maxCompletionTokens)
                .maxTokens(maxTokens)
                .build();

        var generationConfig = send(client, request);

        assertThat(generationConfig.has("maxOutputTokens") ? generationConfig.get("maxOutputTokens").asInt() : null)
                .isEqualTo(expected);
    }

    static Stream<Arguments> thinkingCases() {
        return Stream.of(Client.values()).flatMap(client -> Stream.of(
                arguments(client, GEMINI_3_MODEL, Map.of("thinking", Map.of("level", "low")),
                        "{\"thinkingLevel\": \"low\"}"),
                arguments(client, GEMINI_2_5_MODEL, Map.of("thinking", Map.of("level", "low")),
                        "{\"thinkingBudget\": 2048}"),
                arguments(client, GEMINI_3_MODEL, Map.of(), null)));
    }

    @ParameterizedTest(name = "{0}: {1} with {2}")
    @MethodSource("thinkingCases")
    @DisplayName("playground: the thinking level from custom_parameters")
    void playgroundSendsTheThinkingLevel(Client client, String model, Map<String, Object> customParameters,
            String expected) throws Exception {
        var request = ChatCompletionRequest.builder()
                .model(model)
                .addUserMessage("hello")
                .customParameters(customParameters)
                .build();

        var generationConfig = send(client, request);

        assertThat(generationConfig.get("thinkingConfig"))
                .isEqualTo(expected == null ? null : JsonUtils.getJsonNodeFromString(expected));
    }

    @Test
    @DisplayName("judge: the rule's temperature, seed and thinking level")
    void judgeSendsTemperatureSeedAndThinking() {
        var modelParameters = new LlmAsJudgeModelParameters(GEMINI_3_MODEL, 0.2, 7,
                JsonUtils.getJsonNodeFromString("{\"thinking\": {\"level\": \"high\"}}"));
        var judge = GeminiTestClients.pointedAt(
                generator.generateChat(LlmProviderClientApiConfig.builder().apiKey("test-key").build(),
                        modelParameters),
                stubBaseUrl());

        judge.chat(UserMessage.from("hello"));

        assertThat(only(sentGenerationConfig(GENERATE_CONTENT_PATH), "temperature", "seed", "thinkingConfig"))
                .isEqualTo(JsonUtils.getJsonNodeFromString(
                        "{\"temperature\": 0.2, \"seed\": 7, \"thinkingConfig\": {\"thinkingLevel\": \"high\"}}"));
    }

    private JsonNode send(Client client, ChatCompletionRequest request) throws Exception {
        switch (client) {
            case CHAT -> GeminiTestClients.pointedAt(generator.newGeminiClient("test-key", request), stubBaseUrl())
                    .chat(UserMessage.from("hello"));
            case STREAMING -> {
                var completed = new CompletableFuture<ChatResponse>();
                GeminiTestClients.pointedAt(generator.newGeminiStreamingClient("test-key", request), stubBaseUrl())
                        .chat(List.of(UserMessage.from("hello")), new StreamingChatResponseHandler() {
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
        }
        return sentGenerationConfig(client == Client.CHAT ? GENERATE_CONTENT_PATH : STREAM_GENERATE_CONTENT_PATH);
    }

    private String stubBaseUrl() {
        return wireMock.runtimeInfo().getHttpBaseUrl() + "/v1beta";
    }

    private JsonNode sentGenerationConfig(String path) {
        var requests = wireMock.server().findAll(postRequestedFor(urlPathMatching(path)));
        assertThat(requests).hasSize(1);

        // get() rather than path(): a MissingNode would let every "not sent" assertion pass on a renamed field.
        var generationConfig = JsonUtils.getJsonNodeFromString(requests.getFirst().getBodyAsString())
                .get("generationConfig");
        assertThat(generationConfig).as("outbound body should carry a generationConfig").isNotNull();
        return generationConfig;
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
