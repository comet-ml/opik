package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Function;
import dev.langchain4j.model.openai.internal.chat.ResponseFormat;
import dev.langchain4j.model.openai.internal.chat.ResponseFormatType;
import dev.langchain4j.model.openai.internal.chat.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.not;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Drives the real langchain4j Responses models against a local stub, so what is asserted is the
 * body that would reach OpenAI. The stub answers 400 because only the outgoing request matters
 * here; a 400 is also not retried, so each call is recorded exactly once.
 */
class LlmProviderOpenAiResponsesReasoningEffortTest {

    private static final String RESPONSES_PATH = "/responses";

    private WireMockServer openAi;

    @BeforeEach
    void setUp() {
        openAi = new WireMockServer(0);
        openAi.start();
        openAi.stubFor(post(urlPathEqualTo(RESPONSES_PATH)).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"stub\",\"type\":\"invalid_request_error\"}}")));
    }

    @AfterEach
    void tearDown() {
        openAi.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"high", "max"})
    void blockingAndStreamingSendTheReasoningEffortNextToTheOtherSettings(String effort) {
        var request = ChatCompletionRequest.builder()
                .model("gpt-6-sol")
                .addUserMessage("hi")
                .reasoningEffort(effort)
                .temperature(0.3)
                .topP(0.9)
                .maxCompletionTokens(512)
                .tools(Tool.from(Function.builder()
                        .name("get_weather")
                        .parameters(Map.of("type", "object"))
                        .build()))
                .responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON_OBJECT).build())
                .build();

        var errors = sendBlockingAndStreaming(request);

        assertThat(errors).hasSize(2).doesNotContainNull();
        openAi.verify(2, responsesRequest()
                .withRequestBody(matchingJsonPath("$.reasoning.effort", equalTo(effort)))
                .withRequestBody(matchingJsonPath("$.temperature", equalTo("0.3")))
                .withRequestBody(matchingJsonPath("$.top_p", equalTo("0.9")))
                .withRequestBody(matchingJsonPath("$.max_output_tokens", equalTo("512")))
                .withRequestBody(matchingJsonPath("$.tools[0].name", equalTo("get_weather")))
                .withRequestBody(matchingJsonPath("$.text.format.type", equalTo("json_object"))));
        openAi.verify(1, responsesRequest()
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true"))));
    }

    @Test
    void blockingAndStreamingOmitReasoningWhenRequestHasNoEffort() {
        var request = requestWithEffort(null);

        var errors = sendBlockingAndStreaming(request);

        assertThat(errors).hasSize(2).doesNotContainNull();
        openAi.verify(2, responsesRequest().withRequestBody(not(matchingJsonPath("$.reasoning"))));
    }

    private List<Throwable> sendBlockingAndStreaming(ChatCompletionRequest request) {
        var provider = new LlmProviderOpenAiResponses(
                new OpenAIClientGenerator(new LlmProviderClientConfig()),
                LlmProviderClientApiConfig.builder()
                        .apiKey("test-key")
                        .baseUrl(openAi.baseUrl())
                        .build());
        var errors = new ArrayList<Throwable>();

        errors.add(catchThrowable(() -> provider.generate(request, "ws-1")));
        provider.generateStream(request, "ws-1", chunk -> {
        }, () -> {
        }, errors::add);

        return errors;
    }

    private static ChatCompletionRequest requestWithEffort(String effort) {
        return ChatCompletionRequest.builder()
                .model("gpt-6-sol")
                .addUserMessage("hi")
                .reasoningEffort(effort)
                .build();
    }

    private static RequestPatternBuilder responsesRequest() {
        return postRequestedFor(urlPathEqualTo(RESPONSES_PATH));
    }
}
