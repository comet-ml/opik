package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomLlmProviderTest {

    private static final String PROVIDER_NAME = "local";
    private static final String MODEL = "llama3.2";
    private static final String COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String COMPLETION = """
            {"id": "chatcmpl-1", "object": "chat.completion", "created": 0, "model": "llama3.2",
             "choices": [{"index": 0, "message": {"role": "assistant", "content": "hi"}, "finish_reason": "stop"}],
             "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
            """;
    private static final String COMPLETION_STREAM = """
            data: {"id": "chatcmpl-1", "object": "chat.completion.chunk", "created": 0, "model": "llama3.2", \
            "choices": [{"index": 0, "delta": {"role": "assistant", "content": "hi"}, "finish_reason": "stop"}]}

            data: [DONE]

            """;

    private WireMockServer wireMock;
    private CustomLlmClientGenerator clientGenerator;

    @BeforeAll
    void setUpAll() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        var clientConfig = new LlmProviderClientConfig();
        clientConfig.setLogRequests(false);
        clientConfig.setLogResponses(false);
        clientGenerator = new CustomLlmClientGenerator(clientConfig, mock(AuthTokenProvider.class));
    }

    @AfterAll
    void tearDownAll() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(COMPLETIONS_PATH)).willReturn(okJson(COMPLETION)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokenLimitCases")
    void generateSendsTheTokenLimitTheProviderReads(
            String name, LlmProvider provider, Integer maxCompletionTokens, Integer maxTokens,
            Integer expectedMaxTokens, Integer expectedMaxCompletionTokens) {
        newProvider(provider).generate(request(maxCompletionTokens, maxTokens), "workspace-id");

        assertTokenLimit(name, sentBody(), expectedMaxTokens, expectedMaxCompletionTokens);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokenLimitCases")
    void generateStreamSendsTheTokenLimitTheProviderReads(
            String name, LlmProvider provider, Integer maxCompletionTokens, Integer maxTokens,
            Integer expectedMaxTokens, Integer expectedMaxCompletionTokens) throws Exception {
        wireMock.stubFor(post(urlEqualTo(COMPLETIONS_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(COMPLETION_STREAM)));
        var done = new CompletableFuture<Void>();

        newProvider(provider).generateStream(request(maxCompletionTokens, maxTokens), "workspace-id",
                response -> {
                }, () -> done.complete(null), done::completeExceptionally);
        done.get(10, TimeUnit.SECONDS);

        assertTokenLimit(name, sentBody(), expectedMaxTokens, expectedMaxCompletionTokens);
    }

    @Test
    void generateKeepsTheRestOfTheRequestForOllama() {
        var request = ChatCompletionRequest.builder()
                .from(request(4000, null))
                .temperature(0.3)
                .topP(0.9)
                .seed(7)
                .customParameters(Map.of("keep_alive", "5m"))
                .build();

        newProvider(LlmProvider.OLLAMA).generate(request, "workspace-id");

        var body = sentBody();
        assertThat(body.get("model").asText()).isEqualTo(MODEL);
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.3);
        assertThat(body.get("top_p").asDouble()).isEqualTo(0.9);
        assertThat(body.get("seed").asInt()).isEqualTo(7);
        assertThat(body.get("keep_alive").asText()).isEqualTo("5m");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(4000);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("penaltyCases")
    void generateSendsOnlyThePenaltiesTheProviderNeeds(
            String name, LlmProvider provider, Double penalty, Double expectedPenalty) {
        newProvider(provider).generate(penaltyRequest(penalty), "workspace-id");

        assertPenalties(name, sentBody(), expectedPenalty);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("penaltyCases")
    void generateStreamSendsOnlyThePenaltiesTheProviderNeeds(
            String name, LlmProvider provider, Double penalty, Double expectedPenalty) throws Exception {
        wireMock.stubFor(post(urlEqualTo(COMPLETIONS_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(COMPLETION_STREAM)));
        var done = new CompletableFuture<Void>();

        newProvider(provider).generateStream(penaltyRequest(penalty), "workspace-id",
                response -> {
                }, () -> done.complete(null), done::completeExceptionally);
        done.get(10, TimeUnit.SECONDS);

        assertPenalties(name, sentBody(), expectedPenalty);
    }

    @ParameterizedTest
    @EnumSource(value = LlmProvider.class, names = {"CUSTOM_LLM", "OLLAMA", "BEDROCK"})
    void generateSendsAnExtraBodyKeyOnceWithTheExtraBodyValue(LlmProvider provider) {
        var request = ChatCompletionRequest.builder()
                .from(request(66, null))
                .temperature(0.25)
                .customParameters(Map.of("temperature", 0.95, "max_completion_tokens", 12, "top_k", 7))
                .build();

        newProvider(provider).generate(request, "workspace-id");

        var rawBody = sentRawBody();
        var body = JsonUtils.getJsonNodeFromString(rawBody);
        assertThat(StringUtils.countMatches(rawBody, "\"temperature\"")).isEqualTo(1);
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.95);
        assertThat(StringUtils.countMatches(rawBody, "\"max_completion_tokens\"")).isEqualTo(1);
        assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(12);
        assertThat(body.get("top_k").asInt()).isEqualTo(7);
    }

    @Test
    void judgeSendsAnExtraBodyKeyOnceWithTheExtraBodyValue() {
        var config = LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl(wireMock.baseUrl() + "/v1")
                .configuration(Map.of("provider_name", PROVIDER_NAME))
                .build();
        var parameters = LlmAsJudgeModelParameters.builder()
                .name("custom-llm/" + PROVIDER_NAME + "/" + MODEL)
                .temperature(0.25)
                .seed(7)
                .customParameters(JsonUtils.getJsonNodeFromString("""
                        {"temperature": 0.95, "seed": 42}
                        """))
                .build();

        clientGenerator.generateChat(config, parameters).chat("hello");

        var rawBody = sentRawBody();
        var body = JsonUtils.getJsonNodeFromString(rawBody);
        assertThat(StringUtils.countMatches(rawBody, "\"temperature\"")).isEqualTo(1);
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.95);
        assertThat(StringUtils.countMatches(rawBody, "\"seed\"")).isEqualTo(1);
        assertThat(body.get("seed").asInt()).isEqualTo(42);
    }

    private static Stream<Arguments> penaltyCases() {
        return Stream.of(
                arguments("Bedrock gets no penalty of 0", LlmProvider.BEDROCK, 0.0, null),
                arguments("Bedrock keeps a penalty the user set", LlmProvider.BEDROCK, 0.5, 0.5),
                arguments("Bedrock gets no penalty when none is set", LlmProvider.BEDROCK, null, null),
                arguments("Ollama keeps a penalty of 0", LlmProvider.OLLAMA, 0.0, 0.0),
                arguments("A custom provider keeps a penalty of 0", LlmProvider.CUSTOM_LLM, 0.0, 0.0));
    }

    private static Stream<Arguments> tokenLimitCases() {
        return Stream.of(
                arguments("Ollama gets the limit as max_tokens", LlmProvider.OLLAMA, 4000, null, 4000, null),
                arguments("Ollama keeps a max_tokens sent on its own", LlmProvider.OLLAMA, null, 300, 300, null),
                arguments("Ollama prefers max_completion_tokens when both are sent", LlmProvider.OLLAMA, 4000,
                        300, 4000, null),
                arguments("Ollama gets no limit for 0", LlmProvider.OLLAMA, 0, null, null, null),
                arguments("Ollama falls back to max_tokens when max_completion_tokens is 0", LlmProvider.OLLAMA,
                        0, 300, 300, null),
                arguments("Ollama gets no limit when none is set", LlmProvider.OLLAMA, null, null, null, null),
                arguments("Bedrock keeps max_completion_tokens", LlmProvider.BEDROCK, 4000, null, null, 4000),
                arguments("Bedrock gets a max_tokens sent on its own as max_completion_tokens", LlmProvider.BEDROCK,
                        null, 300, null, 300),
                arguments("Bedrock prefers max_completion_tokens when both are sent", LlmProvider.BEDROCK, 4000,
                        300, null, 4000),
                arguments("Bedrock gets no limit for 0", LlmProvider.BEDROCK, 0, null, null, null),
                arguments("Bedrock gets no limit for a max_tokens of 0", LlmProvider.BEDROCK, null, 0, null, null),
                arguments("Bedrock falls back to max_tokens when max_completion_tokens is 0", LlmProvider.BEDROCK,
                        0, 300, null, 300),
                arguments("Bedrock drops a max_tokens of 0 sent next to max_completion_tokens",
                        LlmProvider.BEDROCK, 4000, 0, null, 4000),
                arguments("Bedrock gets no limit when none is set", LlmProvider.BEDROCK, null, null, null, null),
                arguments("A custom provider keeps max_completion_tokens", LlmProvider.CUSTOM_LLM, 4000, null,
                        null, 4000),
                arguments("A custom provider keeps a 0 as sent", LlmProvider.CUSTOM_LLM, 0, null, null, 0),
                arguments("A custom provider keeps max_tokens as sent", LlmProvider.CUSTOM_LLM, null, 300, 300,
                        null),
                arguments("A provider of unknown type is left as sent", null, 4000, null, null, 4000));
    }

    private CustomLlmProvider newProvider(LlmProvider provider) {
        var config = LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl(wireMock.baseUrl() + "/v1")
                .configuration(Map.of("provider_name", PROVIDER_NAME))
                .provider(provider)
                .build();
        return new CustomLlmProvider(clientGenerator.newCustomLlmClient(config), config.configuration(), provider);
    }

    private ChatCompletionRequest request(Integer maxCompletionTokens, Integer maxTokens) {
        return ChatCompletionRequest.builder()
                .model("custom-llm/" + PROVIDER_NAME + "/" + MODEL)
                .messages(List.of(UserMessage.builder().content("hello").build()))
                .maxCompletionTokens(maxCompletionTokens)
                .maxTokens(maxTokens)
                .build();
    }

    private ChatCompletionRequest penaltyRequest(Double penalty) {
        return ChatCompletionRequest.builder()
                .from(request(4000, null))
                .frequencyPenalty(penalty)
                .presencePenalty(penalty)
                .build();
    }

    private JsonNode sentBody() {
        return JsonUtils.getJsonNodeFromString(sentRawBody());
    }

    private String sentRawBody() {
        var requests = wireMock.findAll(postRequestedFor(urlEqualTo(COMPLETIONS_PATH)));
        assertThat(requests).hasSize(1);
        return requests.getFirst().getBodyAsString();
    }

    private void assertPenalties(String name, JsonNode body, Double expectedPenalty) {
        assertThat(body.has("frequency_penalty") ? body.get("frequency_penalty").asDouble() : null)
                .as("[%s] frequency_penalty", name)
                .isEqualTo(expectedPenalty);
        assertThat(body.has("presence_penalty") ? body.get("presence_penalty").asDouble() : null)
                .as("[%s] presence_penalty", name)
                .isEqualTo(expectedPenalty);
    }

    private void assertTokenLimit(
            String name, JsonNode body, Integer expectedMaxTokens, Integer expectedMaxCompletionTokens) {
        assertThat(body.has("max_tokens") ? body.get("max_tokens").asInt() : null)
                .as("[%s] max_tokens", name)
                .isEqualTo(expectedMaxTokens);
        assertThat(body.has("max_completion_tokens") ? body.get("max_completion_tokens").asInt() : null)
                .as("[%s] max_completion_tokens", name)
                .isEqualTo(expectedMaxCompletionTokens);
    }
}
