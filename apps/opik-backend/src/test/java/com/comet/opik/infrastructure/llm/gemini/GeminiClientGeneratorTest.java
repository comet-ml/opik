package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.BadRequestException;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @BeforeAll
    void setUpAll() {
        var clientConfig = new LlmProviderClientConfig();
        clientConfig.setCallTimeout(Duration.seconds(10));
        clientConfig.setLogRequests(false);
        clientConfig.setLogResponses(false);
        generator = new GeminiClientGenerator(clientConfig, LlmTestClients.REMOTE_MEDIA_INLINER,
                LlmTestClients.VIDEO_MIME_TYPE_UTILS, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);
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
        return Stream.of(
                arguments("both as set", 0.3, 0.8, "{\"temperature\": 0.3, \"topP\": 0.8}"),
                arguments("a temperature of 0 is still sent", 0.0, null, "{\"temperature\": 0.0}"),
                arguments("neither set sends neither", null, null, "{}"));
    }

    @ParameterizedTest(name = "chat: {0}")
    @MethodSource("samplingCases")
    @DisplayName("playground chat: the panel's temperature and top_p")
    void chatSendsTheSamplingParams(String name, Double temperature, Double topP, String expected) {
        assertThat(only(sendChat(samplingRequest(temperature, topP)), "temperature", "topP"))
                .isEqualTo(JsonUtils.getJsonNodeFromString(expected));
    }

    @ParameterizedTest(name = "streaming: {0}")
    @MethodSource("samplingCases")
    @DisplayName("playground streaming: the panel's temperature and top_p")
    void streamingSendsTheSamplingParams(String name, Double temperature, Double topP, String expected)
            throws Exception {
        assertThat(only(sendStreaming(samplingRequest(temperature, topP)), "temperature", "topP"))
                .isEqualTo(JsonUtils.getJsonNodeFromString(expected));
    }

    static Stream<Arguments> maxOutputTokensCases() {
        return Stream.of(
                arguments("only max_completion_tokens", 2048, null, 2048),
                arguments("only max_tokens", null, 512, 512),
                arguments("both, max_completion_tokens wins", 2048, 512, 2048),
                arguments("max_completion_tokens 0 falls back to max_tokens", 0, 512, 512),
                arguments("max_completion_tokens 0, the slider's minimum", 0, null, null),
                arguments("max_tokens 0", null, 0, null),
                arguments("negative max_completion_tokens only", -1, null, null),
                arguments("negative max_completion_tokens falls back to max_tokens", -1, 512, 512),
                arguments("both negative", -1, -5, null),
                arguments("neither", null, null, null));
    }

    @ParameterizedTest(name = "chat: {0}")
    @MethodSource("maxOutputTokensCases")
    @DisplayName("playground chat: the max output tokens cap, never 0 or below, the same rule as Vertex AI")
    void chatSendsTheResolvedCap(String name, Integer maxCompletionTokens, Integer maxTokens, Integer expected) {
        assertSentMaxOutputTokens(sendChat(capRequest(maxCompletionTokens, maxTokens)), expected);
    }

    @ParameterizedTest(name = "streaming: {0}")
    @MethodSource("maxOutputTokensCases")
    @DisplayName("playground streaming: the max output tokens cap, never 0 or below, the same rule as Vertex AI")
    void streamingSendsTheResolvedCap(String name, Integer maxCompletionTokens, Integer maxTokens, Integer expected)
            throws Exception {
        assertSentMaxOutputTokens(sendStreaming(capRequest(maxCompletionTokens, maxTokens)), expected);
    }

    static Stream<Arguments> thinkingCases() {
        return Stream.of(
                arguments(GEMINI_3_MODEL, Map.of("thinking", Map.of("level", "low")),
                        "{\"thinkingLevel\": \"low\"}"),
                arguments(GEMINI_2_5_MODEL, Map.of("thinking", Map.of("level", "low")),
                        "{\"thinkingBudget\": 2048}"),
                arguments(GEMINI_3_MODEL, Map.of(), null));
    }

    @ParameterizedTest(name = "chat: {0} with {1}")
    @MethodSource("thinkingCases")
    @DisplayName("playground chat: the thinking level from custom_parameters")
    void chatSendsTheThinkingLevel(String model, Map<String, Object> customParameters, String expected) {
        assertThat(sendChat(thinkingRequest(model, customParameters)).get("thinkingConfig"))
                .isEqualTo(expected == null ? null : JsonUtils.getJsonNodeFromString(expected));
    }

    @ParameterizedTest(name = "streaming: {0} with {1}")
    @MethodSource("thinkingCases")
    @DisplayName("playground streaming: the thinking level from custom_parameters")
    void streamingSendsTheThinkingLevel(String model, Map<String, Object> customParameters, String expected)
            throws Exception {
        assertThat(sendStreaming(thinkingRequest(model, customParameters)).get("thinkingConfig"))
                .isEqualTo(expected == null ? null : JsonUtils.getJsonNodeFromString(expected));
    }

    @Test
    @DisplayName("judge: the rule's temperature, seed and thinking level")
    void judgeSendsTemperatureSeedAndThinking() {
        var modelParameters = new LlmAsJudgeModelParameters(GEMINI_3_MODEL, 0.2, 7,
                JsonUtils.getJsonNodeFromString("{\"thinking\": {\"level\": \"high\"}}"), null);
        var judge = GeminiTestClients.pointedAt(
                generator.generateChat(LlmProviderClientApiConfig.builder().apiKey("test-key").build(),
                        modelParameters),
                stubBaseUrl());

        judge.chat(UserMessage.from("hello"));

        assertThat(only(sentGenerationConfig(GENERATE_CONTENT_PATH), "temperature", "seed", "thinkingConfig"))
                .isEqualTo(JsonUtils.getJsonNodeFromString(
                        "{\"temperature\": 0.2, \"seed\": 7, \"thinkingConfig\": {\"thinkingLevel\": \"high\"}}"));
    }

    @Test
    @DisplayName("judge: a tool loop's next round sends back the thought signature of each function call")
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
        var judge = GeminiTestClients.pointedAt(
                generator.generateChat(LlmProviderClientApiConfig.builder().apiKey("test-key").build(),
                        new LlmAsJudgeModelParameters(GEMINI_3_MODEL, null, null, null, null)),
                stubBaseUrl());
        var question = UserMessage.from("Read trace t1");

        var call = judge.chat(ChatRequest.builder().messages(question).build()).aiMessage();
        judge.chat(ChatRequest.builder()
                .messages(question, call,
                        ToolExecutionResultMessage.from(call.toolExecutionRequests().getFirst(), "banana"))
                .build());

        var requests = wireMock.server().findAll(postRequestedFor(urlPathMatching(GENERATE_CONTENT_PATH)));
        assertThat(requests).hasSize(2);
        var sentCalls = JsonUtils.getJsonNodeFromString(requests.get(1).getBodyAsString()).findParents("functionCall");
        assertThat(sentCalls).hasSize(1);
        assertThat(sentCalls.getFirst().path("thoughtSignature").asText()).isEqualTo(signature);
    }

    private static final Map<String, Object> AI_STUDIO_SETTINGS = Map.ofEntries(
            Map.entry("temperature", 1.5),
            Map.entry("topP", 0.9),
            Map.entry("topK", 40),
            Map.entry("seed", 7),
            Map.entry("frequencyPenalty", 0.4),
            Map.entry("presencePenalty", 0.6),
            Map.entry("maxOutputTokens", 300),
            Map.entry("stopSequences", List.of("END")),
            Map.entry("responseLogprobs", true),
            Map.entry("logprobs", 3),
            Map.entry("mediaResolution", "MEDIA_RESOLUTION_LOW"),
            Map.entry("safetySettings", List.of(
                    Map.of("category", "HARM_CATEGORY_HATE_SPEECH", "threshold", "BLOCK_NONE"))));

    private static final String AI_STUDIO_GENERATION_CONFIG = """
            {"temperature": 1.5, "topP": 0.9, "topK": 40, "seed": 7, "frequencyPenalty": 0.4, \
            "presencePenalty": 0.6, "maxOutputTokens": 300, "stopSequences": ["END"], "responseLogprobs": true, \
            "logprobs": 3, "mediaResolution": "MEDIA_RESOLUTION_LOW"}""";

    private static final String[] AI_STUDIO_GENERATION_FIELDS = {"temperature", "topP", "topK", "seed",
            "frequencyPenalty", "presencePenalty", "maxOutputTokens", "stopSequences", "responseLogprobs", "logprobs",
            "mediaResolution"};

    @Test
    @DisplayName("playground chat: each custom setting AI Studio takes is sent instead of the panel's value")
    void chatSendsTheCustomSettings() {
        assertThat(only(sendChat(customRequest(AI_STUDIO_SETTINGS)), AI_STUDIO_GENERATION_FIELDS))
                .isEqualTo(JsonUtils.getJsonNodeFromString(AI_STUDIO_GENERATION_CONFIG));
        assertThat(sentBody(GENERATE_CONTENT_PATH).get("safetySettings")).isEqualTo(JsonUtils.getJsonNodeFromString(
                "[{\"category\": \"HARM_CATEGORY_HATE_SPEECH\", \"threshold\": \"BLOCK_NONE\"}]"));
    }

    @Test
    @DisplayName("playground streaming: each custom setting AI Studio takes is sent instead of the panel's value")
    void streamingSendsTheCustomSettings() throws Exception {
        assertThat(only(sendStreaming(customRequest(AI_STUDIO_SETTINGS)), AI_STUDIO_GENERATION_FIELDS))
                .isEqualTo(JsonUtils.getJsonNodeFromString(AI_STUDIO_GENERATION_CONFIG));
        assertThat(sentBody(STREAM_GENERATE_CONTENT_PATH).get("safetySettings")).isEqualTo(
                JsonUtils.getJsonNodeFromString(
                        "[{\"category\": \"HARM_CATEGORY_HATE_SPEECH\", \"threshold\": \"BLOCK_NONE\"}]"));
    }

    @Test
    @DisplayName("playground chat: the thinking level goes out next to the custom settings")
    void chatSendsTheThinkingLevelWithCustomSettings() {
        var generationConfig = sendChat(
                thinkingRequest(GEMINI_3_MODEL, Map.of("thinking", Map.of("level", "low"), "topK", 40)));

        assertThat(only(generationConfig, "thinkingConfig", "topK")).isEqualTo(JsonUtils.getJsonNodeFromString(
                "{\"thinkingConfig\": {\"thinkingLevel\": \"low\"}, \"topK\": 40}"));
    }

    static Stream<Arguments> refusedCases() {
        return Stream.of(
                arguments(Map.of("responseMimeType", "application/json"),
                        "custom_parameters.responseMimeType can't be sent to Gemini on Google AI Studio, Vertex AI "
                                + "takes it. AI Studio takes: temperature, topP, topK, seed, frequencyPenalty, "
                                + "presencePenalty, maxOutputTokens, stopSequences, responseLogprobs, logprobs, "
                                + "mediaResolution, safetySettings"),
                arguments(Map.of("top_k", 40),
                        "custom_parameters.top_k isn't a Gemini setting. Use the names of Google's "
                                + "GenerateContentConfig, like topK or responseMimeType"),
                arguments(Map.of("topK", 40.5), "custom_parameters.topK must be a whole number on Google AI Studio"),
                arguments(Map.of("temperature", "hot"), "custom_parameters.temperature has a value of the wrong type"),
                arguments(Map.of("mediaResolution", "MEDIA_RESOLUTION_TINY"),
                        "custom_parameters.mediaResolution 'MEDIA_RESOLUTION_TINY' isn't one Google AI Studio takes"),
                arguments(Map.of("safetySettings", List.of(Map.of("category", "HARM_CATEGORY_HATE_SPEECH",
                        "threshold", "BLOCK_NONE", "method", "SEVERITY"))),
                        "custom_parameters.safetySettings[0].method can't be sent to Gemini on Google AI Studio, "
                                + "Vertex AI takes it"),
                arguments(Map.of("safetySettings", List.of(Map.of("category", "HARM_CATEGORY_HATE_SPEECH"))),
                        "custom_parameters.safetySettings[0].threshold is required"),
                arguments(Map.of("systemInstruction", Map.of("parts", List.of(Map.of("text", "be brief")))),
                        "custom_parameters.systemInstruction is set by Opik from the request and cannot be replaced"));
    }

    @ParameterizedTest
    @MethodSource("refusedCases")
    @DisplayName("playground: a key AI Studio wouldn't get is refused before the call")
    void refusesBeforeTheCall(Map<String, Object> customParameters, String message) {
        var provider = new LlmProviderGemini(generator,
                LlmProviderClientApiConfig.builder().apiKey("test-key").build());

        assertThatThrownBy(() -> provider.validateRequest(customRequest(customParameters)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(message);
        assertThatThrownBy(() -> generator.newGeminiStreamingClient("test-key", customRequest(customParameters)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(message);
    }

    @Test
    @DisplayName("judge: still reads only the thinking level from custom_parameters")
    void judgeReadsOnlyTheThinkingLevel() {
        var modelParameters = new LlmAsJudgeModelParameters(GEMINI_3_MODEL, null, null,
                JsonUtils.getJsonNodeFromString(
                        "{\"thinking\": {\"level\": \"high\"}, \"topK\": 40, \"reasoning_effort\": \"high\"}"),
                null);
        var judge = GeminiTestClients.pointedAt(
                generator.generateChat(LlmProviderClientApiConfig.builder().apiKey("test-key").build(),
                        modelParameters),
                stubBaseUrl());

        judge.chat(UserMessage.from("hello"));

        assertThat(only(sentGenerationConfig(GENERATE_CONTENT_PATH), "thinkingConfig", "topK"))
                .isEqualTo(JsonUtils.getJsonNodeFromString("{\"thinkingConfig\": {\"thinkingLevel\": \"high\"}}"));
    }

    private static ChatCompletionRequest customRequest(Map<String, Object> customParameters) {
        return ChatCompletionRequest.builder()
                .model(GEMINI_2_5_MODEL)
                .addUserMessage("hello")
                .temperature(0.2)
                .maxCompletionTokens(1000)
                .customParameters(customParameters)
                .build();
    }

    private static ChatCompletionRequest samplingRequest(Double temperature, Double topP) {
        return ChatCompletionRequest.builder()
                .model(GEMINI_2_5_MODEL)
                .addUserMessage("hello")
                .temperature(temperature)
                .topP(topP)
                .build();
    }

    private static ChatCompletionRequest capRequest(Integer maxCompletionTokens, Integer maxTokens) {
        return ChatCompletionRequest.builder()
                .model(GEMINI_2_5_MODEL)
                .addUserMessage("hello")
                .maxCompletionTokens(maxCompletionTokens)
                .maxTokens(maxTokens)
                .build();
    }

    private static ChatCompletionRequest thinkingRequest(String model, Map<String, Object> customParameters) {
        return ChatCompletionRequest.builder()
                .model(model)
                .addUserMessage("hello")
                .customParameters(customParameters)
                .build();
    }

    private static void assertSentMaxOutputTokens(JsonNode generationConfig, Integer expected) {
        assertThat(generationConfig.has("maxOutputTokens") ? generationConfig.get("maxOutputTokens").asInt() : null)
                .isEqualTo(expected);
    }

    private JsonNode sendChat(ChatCompletionRequest request) {
        GeminiTestClients.pointedAt(generator.newGeminiClient("test-key", request), stubBaseUrl())
                .chat(UserMessage.from("hello"));
        return sentGenerationConfig(GENERATE_CONTENT_PATH);
    }

    private JsonNode sendStreaming(ChatCompletionRequest request) throws Exception {
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
        return sentGenerationConfig(STREAM_GENERATE_CONTENT_PATH);
    }

    private String stubBaseUrl() {
        return wireMock.runtimeInfo().getHttpBaseUrl() + "/v1beta";
    }

    private JsonNode sentBody(String path) {
        var requests = wireMock.server().findAll(postRequestedFor(urlPathMatching(path)));
        assertThat(requests).hasSize(1);
        return JsonUtils.getJsonNodeFromString(requests.getFirst().getBodyAsString());
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
