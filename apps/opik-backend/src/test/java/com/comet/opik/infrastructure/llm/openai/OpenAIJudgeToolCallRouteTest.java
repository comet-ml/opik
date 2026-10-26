package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.ModelParams;
import com.comet.opik.api.ModelParams.ChatCompletionsTools;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.OpenAiClientConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

/**
 * Which endpoint a tool-calling judge reaches on an OpenAI key in Chat Completions mode, and with which reasoning
 * effort, per the catalog's tools fact. The keys below leave their own base URL blank and reach the stub through the
 * installation-wide URL, which is how a judge on api.openai.com looks to the route choice.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAIJudgeToolCallRouteTest {

    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final String RESPONSES_PATH = "/responses";
    private static final String COMPLETION = """
            {"id": "chatcmpl-1", "object": "chat.completion", "created": 0, "model": "gpt-6-luna",
             "choices": [{"index": 0, "message": {"role": "assistant", "content": "{}"}, "finish_reason": "stop"}],
             "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
            """;

    private static final ChatRequest TOOL_REQUEST = ChatRequest.builder()
            .messages(UserMessage.from("Score the trace"))
            .toolSpecifications(ToolSpecification.builder()
                    .name("read")
                    .description("Read the trace")
                    .parameters(JsonObjectSchema.builder().addStringProperty("id").required("id").build())
                    .build())
            .toolChoice(ToolChoice.REQUIRED)
            .build();

    private WireMockServer wireMock;
    private OpenAILlmServiceProvider serviceProvider;

    @BeforeAll
    void setUpAll() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        var clientConfig = new LlmProviderClientConfig();
        clientConfig.setLogRequests(false);
        clientConfig.setLogResponses(false);
        clientConfig.setOpenAiClient(new OpenAiClientConfig(wireMock.baseUrl()));
        serviceProvider = new OpenAILlmServiceProvider(new OpenAIClientGenerator(clientConfig),
                mock(LlmProviderFactory.class));
    }

    @AfterAll
    void tearDownAll() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(COMPLETIONS_PATH)).willReturn(okJson(COMPLETION)));
        // Only the outgoing request matters; a 400 is not retried, so each call is recorded once.
        wireMock.stubFor(post(urlEqualTo(RESPONSES_PATH)).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"stub\",\"type\":\"invalid_request_error\"}}")));
    }

    @ParameterizedTest
    @EnumSource(ChatCompletionsTools.class)
    void aModelLimitedOnChatCompletionsCallsToolsOnTheResponsesApiWithTheRulesEffort(ChatCompletionsTools rule) {
        callTools(keyInChatCompletionsMode(null), judge("{\"reasoning_effort\": \"low\"}"), tools(rule));

        var body = sentBody(RESPONSES_PATH);
        assertThat(body.at("/reasoning")).isEqualTo(JsonUtils.getJsonNodeFromString("{\"effort\": \"low\"}"));
        assertThat(body.at("/tool_choice").asText()).isEqualTo("required");
        assertThat(body.at("/tools/0/name").asText()).isEqualTo("read");
        assertThat(requestsTo(COMPLETIONS_PATH)).isZero();
    }

    @Test
    void aKeyWithItsOwnBaseUrlKeepsChatCompletionsAndGivesUpReasoningWhereTheModelAllowsIt() {
        callTools(keyInChatCompletionsMode(wireMock.baseUrl()), judge("{\"reasoning_effort\": \"high\"}"),
                tools(ChatCompletionsTools.EFFORT_NONE_ONLY));

        var rawBody = sentRawBody(COMPLETIONS_PATH);
        var body = JsonUtils.getJsonNodeFromString(rawBody);
        assertThat(StringUtils.countMatches(rawBody, "\"reasoning_effort\"")).isEqualTo(1);
        assertThat(body.at("/reasoning_effort").asText()).isEqualTo("none");
        assertThat(body.at("/tool_choice").asText()).isEqualTo("required");
        assertThat(body.at("/tools/0/function/name").asText()).isEqualTo("read");
        assertThat(requestsTo(RESPONSES_PATH)).isZero();
    }

    @Test
    void aModelThatTakesNoToolsOnChatCompletionsGoesToTheResponsesApiEvenBehindTheKeysBaseUrl() {
        callTools(keyInChatCompletionsMode(wireMock.baseUrl()), judge(null), tools(ChatCompletionsTools.REFUSED));

        assertThat(sentBody(RESPONSES_PATH).at("/tool_choice").asText()).isEqualTo("required");
        assertThat(requestsTo(COMPLETIONS_PATH)).isZero();
    }

    @Test
    void aModelWithoutAToolsFactCallsToolsWhereTheKeySendsIt() {
        callTools(keyInChatCompletionsMode(null), judge(null), null);

        var body = sentBody(COMPLETIONS_PATH);
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.at("/tool_choice").asText()).isEqualTo("required");
        assertThat(requestsTo(RESPONSES_PATH)).isZero();
    }

    @Test
    void aKeyInResponsesModeKeepsItsRouteAndTheRulesEffort() {
        var key = LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .configuration(Map.of("openai_pipeline_mode", "responses_api"))
                .build();

        callTools(key, judge("{\"reasoning_effort\": \"high\"}"), tools(ChatCompletionsTools.EFFORT_NONE_ONLY));

        assertThat(sentBody(RESPONSES_PATH).at("/reasoning"))
                .isEqualTo(JsonUtils.getJsonNodeFromString("{\"effort\": \"high\"}"));
        assertThat(requestsTo(COMPLETIONS_PATH)).isZero();
    }

    private static LlmProviderClientApiConfig keyInChatCompletionsMode(String baseUrl) {
        return LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl(baseUrl)
                .build();
    }

    private static LlmAsJudgeModelParameters judge(String customParameters) {
        return LlmAsJudgeModelParameters.builder()
                .name("gpt-6-luna")
                .customParameters(customParameters == null ? null : JsonUtils.getJsonNodeFromString(customParameters))
                .build();
    }

    private static ModelParams.ToolsParam tools(ChatCompletionsTools chatCompletions) {
        return ModelParams.ToolsParam.builder().chatCompletions(chatCompletions).build();
    }

    private void callTools(LlmProviderClientApiConfig key, LlmAsJudgeModelParameters judge,
            ModelParams.ToolsParam tools) {
        var model = serviceProvider.getToolCallingLanguageModel(key, judge, tools);
        catchThrowable(() -> model.chat(TOOL_REQUEST));
    }

    private long requestsTo(String path) {
        return wireMock.findAll(postRequestedFor(urlEqualTo(path))).size();
    }

    private JsonNode sentBody(String path) {
        return JsonUtils.getJsonNodeFromString(sentRawBody(path));
    }

    private String sentRawBody(String path) {
        var requests = wireMock.findAll(postRequestedFor(urlEqualTo(path)));
        assertThat(requests).hasSize(1);
        return requests.getFirst().getBodyAsString();
    }
}
