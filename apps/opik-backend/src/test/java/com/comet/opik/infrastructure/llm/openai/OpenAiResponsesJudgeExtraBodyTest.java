package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Drives a judge's real langchain4j Responses model against a local stub, so what is asserted is the
 * body that would reach OpenAI. The stub answers 400 because only the outgoing request matters
 * here; a 400 is also not retried, so each call is recorded exactly once.
 */
class OpenAiResponsesJudgeExtraBodyTest {

    private static final String RESPONSES_PATH = "/responses";

    // A key sent twice would otherwise parse as its last copy and hide the duplicate.
    private static final JsonMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private static final ChatRequest JUDGE_REQUEST = ChatRequest.builder()
            .messages(UserMessage.from("Score the answer"))
            .responseFormat(ResponseFormat.builder()
                    .type(ResponseFormatType.JSON)
                    .jsonSchema(JsonSchema.builder()
                            .name("scoring_schema")
                            .rootElement(JsonObjectSchema.builder().addStringProperty("reason").build())
                            .build())
                    .build())
            .build();

    private static final ObjectNode JUDGE_BODY = parse("""
            {"model":"gpt-6-sol",
             "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"Score the answer"}]}],
             "store":false,"temperature":0.2,
             "text":{"format":{"type":"json_schema","name":"scoring_schema","strict":false,
                               "schema":{"type":"object","properties":{"reason":{"type":"string"}},"required":[]}}}}
            """);

    private WireMockServer openAi;
    private OpenAIClientGenerator generator;

    @BeforeEach
    void setUp() {
        openAi = new WireMockServer(0);
        openAi.start();
        openAi.stubFor(post(urlPathEqualTo(RESPONSES_PATH)).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"stub\",\"type\":\"invalid_request_error\"}}")));
        generator = new OpenAIClientGenerator(new LlmProviderClientConfig(), LlmTestClients.RESPONSES_API_HTTP_CLIENT);
    }

    @AfterEach
    void tearDown() {
        openAi.stop();
    }

    @Test
    void judgeSendsItsCustomParametersWithTheChatCompletionsKeysUnderTheirResponsesNames() {
        var sent = sentBody(JsonUtils.getJsonNodeFromString("""
                {"max_tokens":300,"reasoning_effort":"low","response_format":{"type":"json_object"},
                 "service_tier":"flex","metadata":{"rule":"r1"}}
                """));

        var expected = JUDGE_BODY.deepCopy()
                .put("max_output_tokens", 300)
                .put("service_tier", "flex")
                .setAll(Map.of(
                        "reasoning", STRICT_JSON.<JsonNode>valueToTree(Map.of("effort", "low")),
                        "metadata", STRICT_JSON.<JsonNode>valueToTree(Map.of("rule", "r1"))));
        assertThat(sent).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("customParametersWithNothingToSend")
    void judgeWithNoCustomParametersToSendSendsOnlyItsOwnSettings(String name, JsonNode customParameters) {
        assertThat(sentBody(customParameters)).isEqualTo(JUDGE_BODY);
    }

    private static Stream<Arguments> customParametersWithNothingToSend() {
        return Stream.of(
                arguments("none", null),
                arguments("an empty object", JsonUtils.getJsonNodeFromString("{}")),
                arguments("a string", JsonUtils.getJsonNodeFromString("\"fast\"")),
                arguments("an array", JsonUtils.getJsonNodeFromString("[1, 2]")),
                arguments("JSON null", JsonUtils.getJsonNodeFromString("null")));
    }

    @Test
    void judgeRejectsAnExtraBodyKeyOpikSetsFromTheRequest() {
        var modelParameters = LlmAsJudgeModelParameters.builder()
                .name("gpt-6-sol")
                .customParameters(JsonUtils.getJsonNodeFromString("{\"model\":\"gpt-other\"}"))
                .build();

        assertThatThrownBy(() -> generator.newOpenAiChatLanguageModel(responsesKey(), modelParameters))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("custom_parameters.model is set by Opik from the request and cannot be replaced");
    }

    private ObjectNode sentBody(JsonNode customParameters) {
        openAi.resetRequests();
        var judge = generator.newOpenAiChatLanguageModel(responsesKey(), LlmAsJudgeModelParameters.builder()
                .name("gpt-6-sol")
                .temperature(0.2)
                .customParameters(customParameters)
                .build());

        assertThat(catchThrowable(() -> judge.chat(JUDGE_REQUEST))).isNotNull();

        var requests = openAi.findAll(postRequestedFor(urlPathEqualTo(RESPONSES_PATH)));
        assertThat(requests).hasSize(1);
        return parse(requests.getFirst().getBodyAsString());
    }

    private static ObjectNode parse(String body) {
        try {
            return STRICT_JSON.readValue(body, ObjectNode.class);
        } catch (Exception exception) {
            throw new AssertionError("Sent body is not valid JSON with unique keys: " + body, exception);
        }
    }

    private LlmProviderClientApiConfig responsesKey() {
        return LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl(openAi.baseUrl())
                .configuration(Map.of("openai_pipeline_mode", "responses_api"))
                .build();
    }
}
