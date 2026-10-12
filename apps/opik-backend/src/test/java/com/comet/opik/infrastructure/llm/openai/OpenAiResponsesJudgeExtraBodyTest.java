package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
        generator = new OpenAIClientGenerator(new LlmProviderClientConfig());
    }

    @AfterEach
    void tearDown() {
        openAi.stop();
    }

    @Test
    void judgeSendsItsCustomParametersWithTheChatCompletionsKeysUnderTheirResponsesNames() {
        var withoutCustomParameters = sentBody(null);

        var sent = sentBody(JsonUtils.getJsonNodeFromString("""
                {"max_tokens":300,"reasoning_effort":"low","response_format":{"type":"json_object"},
                 "service_tier":"flex","metadata":{"rule":"r1"}}
                """));

        var expected = withoutCustomParameters.deepCopy()
                .put("max_output_tokens", 300)
                .put("service_tier", "flex")
                .setAll(Map.of(
                        "reasoning", STRICT_JSON.<JsonNode>valueToTree(Map.of("effort", "low")),
                        "metadata", STRICT_JSON.<JsonNode>valueToTree(Map.of("rule", "r1"))));
        assertThat(sent).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "\"fast\"", "[1, 2]", "null"})
    void judgeWithoutAnObjectOfCustomParametersSendsOnlyItsOwnSettings(String customParameters) {
        assertThat(sentBody(JsonUtils.getJsonNodeFromString(customParameters))).isEqualTo(sentBody(null));
    }

    private ObjectNode sentBody(JsonNode customParameters) {
        openAi.resetRequests();
        var config = LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl(openAi.baseUrl())
                .configuration(Map.of("openai_pipeline_mode", "responses_api"))
                .build();
        var judge = generator.newOpenAiChatLanguageModel(config, LlmAsJudgeModelParameters.builder()
                .name("gpt-6-sol")
                .temperature(0.2)
                .customParameters(customParameters)
                .build());

        assertThat(catchThrowable(() -> judge.chat(JUDGE_REQUEST))).isNotNull();

        var requests = openAi.findAll(postRequestedFor(urlPathEqualTo(RESPONSES_PATH)));
        assertThat(requests).hasSize(1);
        try {
            return STRICT_JSON.readValue(requests.getFirst().getBodyAsString(), ObjectNode.class);
        } catch (Exception exception) {
            throw new AssertionError("Sent body is not valid JSON with unique keys: "
                    + requests.getFirst().getBodyAsString(), exception);
        }
    }
}
