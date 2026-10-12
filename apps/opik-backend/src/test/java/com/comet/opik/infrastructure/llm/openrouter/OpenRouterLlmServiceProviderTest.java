package com.comet.opik.infrastructure.llm.openrouter;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.domain.llm.structuredoutput.ToolCallingStrategy;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.google.common.collect.Sets;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenRouterLlmServiceProviderTest {

    private static final String COMPLETIONS_PATH = "/api/v1/chat/completions";
    private static final String COMPLETION = """
            {"id": "gen-1", "object": "chat.completion", "created": 0, "model": "openai/gpt-4o",
             "choices": [{"index": 0, "message": {"role": "assistant", "content": "hi"}, "finish_reason": "stop"}],
             "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
            """;
    private static final String COMPLETION_STREAM = """
            data: {"id": "gen-1", "object": "chat.completion.chunk", "created": 0, "model": "openai/gpt-4o", \
            "choices": [{"index": 0, "delta": {"role": "assistant", "content": "hi"}, "finish_reason": "stop"}]}

            data: [DONE]

            """;

    private static final String PLAYGROUND_BODY = """
            {"model": "openai/gpt-4o", "messages": [{"role": "user", "content": "hello"}], "stream": %s,
             "max_tokens": 512, "temperature": 0.7,
             "custom_parameters": {"top_k": 40, "min_p": 0.1, "top_a": 0.2, "repetition_penalty": 1.1}}
            """;
    private static final JsonNode PANEL_PARAMETERS = JsonUtils.getJsonNodeFromString("""
            {"max_tokens": 512, "temperature": 0.7, "top_k": 40, "min_p": 0.1, "top_a": 0.2, "repetition_penalty": 1.1}
            """);

    private WireMockServer wireMock;
    private OpenRouterLlmServiceProvider serviceProvider;
    private LlmProviderService provider;

    @BeforeAll
    void setUpAll() throws IOException {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        var clientConfig = new LlmProviderClientConfig();
        clientConfig.setLogRequests(false);
        clientConfig.setLogResponses(false);
        clientConfig.setOpenRouterUrl(wireMock.baseUrl() + "/api/v1");

        var clientGenerator = new OpenRouterModule().clientGenerator(clientConfig);
        serviceProvider = new OpenRouterLlmServiceProvider(clientGenerator, mock(LlmProviderFactory.class));
        provider = serviceProvider.getService(LlmProviderClientApiConfig.builder().apiKey("test-key").build());
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

    @Test
    void generateSendsThePanelParametersAsTopLevelKeys() {
        provider.generate(playgroundRequest(false), "workspace-id");

        assertPanelParametersSent(sentBody());
    }

    @Test
    void generateStreamSendsThePanelParametersAsTopLevelKeys() throws Exception {
        wireMock.stubFor(post(urlEqualTo(COMPLETIONS_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(COMPLETION_STREAM)));
        var done = new CompletableFuture<Void>();

        provider.generateStream(playgroundRequest(true), "workspace-id",
                response -> {
                }, () -> done.complete(null), done::completeExceptionally);
        done.get(10, TimeUnit.SECONDS);

        assertPanelParametersSent(sentBody());
    }

    @Test
    void generateSendsACustomParameterThatRepeatsATypedFieldOnceWithTheCustomValue() {
        var request = JsonUtils.readValue("""
                {"model": "openai/gpt-4o", "messages": [{"role": "user", "content": "hello"}], "stream": false,
                 "temperature": 0.7, "custom_parameters": {"temperature": 0.2, "top_k": 40}}
                """, ChatCompletionRequest.class);

        provider.generate(request, "workspace-id");

        var rawBody = sentRawBody();
        var body = JsonUtils.getJsonNodeFromString(rawBody);
        assertThat(StringUtils.countMatches(rawBody, "\"temperature\"")).isEqualTo(1);
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.get("top_k").asInt()).isEqualTo(40);
    }

    @Test
    void generateSendsACustomTokenLimitUnderTheOtherNameInPlaceOfTheTypedLimit() {
        var request = JsonUtils.readValue("""
                {"model": "openai/gpt-4o", "messages": [{"role": "user", "content": "hello"}], "stream": false,
                 "max_tokens": 30, "custom_parameters": {"max_completion_tokens": 12}}
                """, ChatCompletionRequest.class);

        provider.generate(request, "workspace-id");

        var body = sentBody();
        assertThat(body.has("max_tokens")).isFalse();
        assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(12);
    }

    @Test
    void judgeSendsTheRuleCustomParametersAsTopLevelKeys() {
        score(LlmAsJudgeModelParameters.builder()
                .name("openai/gpt-4o")
                .temperature(0.0)
                .customParameters(JsonUtils.getJsonNodeFromString("""
                        {"top_k": 40, "min_p": 0.1, "reasoning": {"effort": "high"}}
                        """))
                .build());

        var body = sentBody();
        assertThat(body.get("top_k").asInt()).isEqualTo(40);
        assertThat(body.get("min_p").asDouble()).isEqualTo(0.1);
        assertThat(body.get("reasoning")).isEqualTo(JsonUtils.getJsonNodeFromString("""
                {"effort": "high"}
                """));
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.0);
        assertThat(body.has("custom_parameters")).isFalse();
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
    }

    @Test
    void judgeSendsACustomTemperatureForAModelThatTakesNoTypedOne() {
        score(LlmAsJudgeModelParameters.builder()
                .name("anthropic/claude-opus-4.7")
                .temperature(0.0)
                .customParameters(JsonUtils.getJsonNodeFromString("""
                        {"temperature": 1.0}
                        """))
                .build());

        var rawBody = sentRawBody();
        assertThat(StringUtils.countMatches(rawBody, "\"temperature\"")).isEqualTo(1);
        assertThat(JsonUtils.getJsonNodeFromString(rawBody).get("temperature").asDouble()).isEqualTo(1.0);
    }

    private void score(LlmAsJudgeModelParameters modelParameters) {
        List<ChatMessage> messages = List.of(UserMessage.from("Score the answer"));
        var schema = List.of(LlmAsJudgeOutputSchema.builder()
                .name("Relevance")
                .type(LlmAsJudgeOutputSchemaType.INTEGER)
                .description("How relevant the answer is")
                .build());
        ChatRequest request = new ToolCallingStrategy()
                .apply(ChatRequest.builder().messages(messages), messages, schema)
                .build();

        serviceProvider.getLanguageModel(LlmProviderClientApiConfig.builder().apiKey("test-key").build(),
                modelParameters).chat(request);
    }

    private ChatCompletionRequest playgroundRequest(boolean stream) {
        return JsonUtils.readValue(PLAYGROUND_BODY.formatted(stream), ChatCompletionRequest.class);
    }

    private JsonNode sentBody() {
        return JsonUtils.getJsonNodeFromString(sentRawBody());
    }

    private String sentRawBody() {
        var requests = wireMock.findAll(postRequestedFor(urlEqualTo(COMPLETIONS_PATH)));
        assertThat(requests).hasSize(1);
        return requests.getFirst().getBodyAsString();
    }

    private void assertPanelParametersSent(JsonNode body) {
        ObjectNode sent = body.deepCopy();
        assertThat(sent.retain(Sets.newHashSet(PANEL_PARAMETERS.fieldNames()))).isEqualTo(PANEL_PARAMETERS);
        assertThat(body.has("custom_parameters")).isFalse();
    }
}
