package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Function;
import dev.langchain4j.model.openai.internal.chat.ResponseFormat;
import dev.langchain4j.model.openai.internal.chat.ResponseFormatType;
import dev.langchain4j.model.openai.internal.chat.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Drives the real langchain4j Responses models against a local stub, so what is asserted is the
 * body that would reach OpenAI. The stub answers 400 because only the outgoing request matters
 * here; a 400 is also not retried, so each call is recorded exactly once.
 */
class LlmProviderOpenAiResponsesExtraBodyTest {

    private static final String RESPONSES_PATH = "/responses";
    private static final String MODEL = "gpt-6-sol";
    private static final String CUSTOM_HEADER = "X-Custom-Header";

    // A key sent twice would otherwise parse as its last copy and hide the duplicate.
    private static final JsonMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private WireMockServer openAi;
    private LlmProviderOpenAiResponses provider;

    @BeforeEach
    void setUp() {
        openAi = new WireMockServer(0);
        openAi.start();
        openAi.stubFor(post(urlPathEqualTo(RESPONSES_PATH)).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"stub\",\"type\":\"invalid_request_error\"}}")));
        provider = new LlmProviderOpenAiResponses(
                new OpenAIClientGenerator(new LlmProviderClientConfig()),
                LlmProviderClientApiConfig.builder()
                        .apiKey("test-key")
                        .baseUrl(openAi.baseUrl())
                        .headers(Map.of(CUSTOM_HEADER, "custom-value"))
                        .build());
    }

    @AfterEach
    void tearDown() {
        openAi.stop();
    }

    @Test
    void blockingAndStreamingAddEachExtraBodyKeyAsGivenNextToTheTypedSettings() {
        var typedRequest = ChatCompletionRequest.builder()
                .model(MODEL)
                .addUserMessage("hi")
                .temperature(0.3)
                .topP(0.9)
                .maxCompletionTokens(512)
                .reasoningEffort("high")
                .tools(Tool.from(Function.builder()
                        .name("get_weather")
                        .parameters(Map.of("type", "object"))
                        .build()))
                .responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON_OBJECT).build())
                .build();
        Map<String, Object> extraBody = Map.of(
                "service_tier", "flex",
                "metadata", Map.of("run", "r1"),
                "include", List.of("reasoning.encrypted_content"));

        var typedOnly = send(typedRequest);
        var withExtraBody = send(withExtraBody(typedRequest, extraBody));

        assertThat(withExtraBody.bodies()).isEqualTo(withKeysReplaced(typedOnly.bodies(), extraBody));
        assertThat(withExtraBody.errorTypes()).isEqualTo(typedOnly.errorTypes());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settingsTheExtraBodyReplaces")
    void blockingAndStreamingSendTheExtraBodyValueInPlaceOfTheSettingOfTheSameName(
            String name, ChatCompletionRequest typedRequest, Map<String, Object> extraBody) {
        var typedOnly = send(typedRequest);
        var withExtraBody = send(withExtraBody(typedRequest, extraBody));

        assertThat(withExtraBody.bodies()).isEqualTo(withKeysReplaced(typedOnly.bodies(), extraBody));
    }

    private static Stream<Arguments> settingsTheExtraBodyReplaces() {
        return Stream.of(
                arguments("max_output_tokens over max_completion_tokens",
                        request().maxCompletionTokens(512).build(), Map.of("max_output_tokens", 64)),
                arguments("max_output_tokens over max_tokens",
                        request().maxTokens(512).build(), Map.of("max_output_tokens", 64)),
                arguments("reasoning over reasoning_effort",
                        request().reasoningEffort("high").build(),
                        Map.of("reasoning", Map.of("summary", "auto"))),
                arguments("text over response_format",
                        request().responseFormat(ResponseFormat.builder()
                                .type(ResponseFormatType.JSON_OBJECT)
                                .build()).build(),
                        Map.of("text", Map.of("verbosity", "low"))),
                arguments("temperature", request().temperature(0.3).build(), Map.of("temperature", 1.2)),
                arguments("temperature as an explicit null",
                        request().temperature(0.3).build(), Collections.singletonMap("temperature", null)),
                arguments("store", request().build(), Map.of("store", true)));
    }

    @Test
    void blockingAndStreamingSendOnlyTheTypedSettingsWithoutAnExtraBody() throws Exception {
        var typedRequest = request().temperature(0.3).maxCompletionTokens(512).build();
        var expected = STRICT_JSON.readValue("""
                {"model":"gpt-6-sol",
                 "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}],
                 "store":false,"temperature":0.3,"max_output_tokens":512}
                """, ObjectNode.class);

        var typedOnly = send(typedRequest);
        var emptyExtraBody = send(withExtraBody(typedRequest, Map.of()));

        assertThat(typedOnly.bodies()).isEqualTo(List.of(expected, expected.deepCopy().put("stream", true)));
        assertThat(emptyExtraBody.bodies()).isEqualTo(typedOnly.bodies());
    }

    private Sent send(ChatCompletionRequest request) {
        openAi.resetRequests();
        var errors = new ArrayList<Throwable>();

        errors.add(catchThrowable(() -> provider.generate(request, "ws-1")));
        provider.generateStream(request, "ws-1", chunk -> {
        }, () -> {
        }, errors::add);

        var requests = openAi.findAll(postRequestedFor(urlPathEqualTo(RESPONSES_PATH)));
        assertThat(requests).hasSize(2).allSatisfy(sent -> {
            assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer test-key");
            assertThat(sent.getHeader(CUSTOM_HEADER)).isEqualTo("custom-value");
        });
        var bodies = requests.stream()
                .map(LoggedRequest::getBodyAsString)
                .map(LlmProviderOpenAiResponsesExtraBodyTest::parseStrictly)
                .toList();
        assertThat(bodies.get(1).get("stream").asBoolean()).isTrue();
        assertThat(errors).hasSize(2).doesNotContainNull();
        return new Sent(bodies, errors.stream().<Class<?>>map(Throwable::getClass).toList());
    }

    private static List<ObjectNode> withKeysReplaced(List<ObjectNode> bodies, Map<String, Object> extraBody) {
        return bodies.stream()
                .map(body -> body.deepCopy().setAll(STRICT_JSON.<ObjectNode>valueToTree(extraBody)))
                .map(ObjectNode.class::cast)
                .toList();
    }

    private static ObjectNode parseStrictly(String body) {
        try {
            return STRICT_JSON.readValue(body, ObjectNode.class);
        } catch (Exception exception) {
            throw new AssertionError("Sent body is not valid JSON with unique keys: " + body, exception);
        }
    }

    private static ChatCompletionRequest withExtraBody(ChatCompletionRequest request,
            Map<String, Object> extraBody) {
        return ChatCompletionRequest.builder().from(request).customParameters(extraBody).build();
    }

    private static ChatCompletionRequest.Builder request() {
        return ChatCompletionRequest.builder().model(MODEL).addUserMessage("hi");
    }

    private record Sent(List<ObjectNode> bodies, List<Class<?>> errorTypes) {
    }
}
