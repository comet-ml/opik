package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
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
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
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

    private static final ObjectNode BASE_BODY = parseStrictly("""
            {"model":"gpt-6-sol",
             "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}],
             "store":false}
            """);

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
                new OpenAIClientGenerator(new LlmProviderClientConfig(), LlmTestClients.RESPONSES_API_HTTP_CLIENT,
                        LlmTestClients.LLM_HTTP_CLIENT_BUILDERS),
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("chatCompletionsKeys")
    void blockingAndStreamingSendAChatCompletionsKeyUnderItsResponsesName(
            String name, ChatCompletionRequest request, Map<String, Object> expectedFields) {
        assertThat(send(request).bodies()).isEqualTo(expectedBodies(expectedFields));
    }

    private static Stream<Arguments> chatCompletionsKeys() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("label", Map.of("type", "string")),
                "required", List.of("label"),
                "additionalProperties", false);
        return Stream.of(
                arguments("max_tokens",
                        withExtraBody(request().maxCompletionTokens(512).build(), Map.of("max_tokens", 64)),
                        Map.of("max_output_tokens", 64)),
                arguments("max_completion_tokens",
                        withExtraBody(request().maxTokens(512).build(), Map.of("max_completion_tokens", 64)),
                        Map.of("max_output_tokens", 64)),
                arguments("max_completion_tokens over a max_tokens it does not read",
                        withExtraBody(request().build(), Map.of("max_completion_tokens", 64, "max_tokens", "lots")),
                        Map.of("max_output_tokens", 64)),
                arguments("reasoning_effort",
                        withExtraBody(request().reasoningEffort("high").build(), Map.of("reasoning_effort", "low")),
                        Map.of("reasoning", Map.of("effort", "low"))),
                arguments("response_format",
                        withExtraBody(request().build(), Map.of("response_format", Map.of(
                                "type", "json_schema",
                                "json_schema", Map.of("name", "answer", "strict", true, "schema", schema)))),
                        Map.of("text", Map.of("format", Map.of(
                                "type", "json_schema", "name", "answer", "strict", true, "schema", schema)))),
                arguments("an explicit null",
                        withExtraBody(request().maxCompletionTokens(512).build(),
                                Collections.singletonMap("max_tokens", null)),
                        Map.of()),
                arguments("next to other extra body keys",
                        withExtraBody(request().build(), Map.of("max_tokens", 64, "service_tier", "flex")),
                        Map.of("max_output_tokens", 64, "service_tier", "flex")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bothNames")
    void blockingAndStreamingSendTheResponsesNameWhenTheExtraBodyHasBothNames(
            String name, Map<String, Object> extraBody, Map<String, Object> expectedFields) {
        assertThat(send(withExtraBody(request().build(), extraBody)).bodies())
                .isEqualTo(expectedBodies(expectedFields));
    }

    private static Stream<Arguments> bothNames() {
        return Stream.of(
                arguments("max_output_tokens",
                        Map.of("max_tokens", 100, "max_output_tokens", 64),
                        Map.of("max_output_tokens", 64)),
                arguments("reasoning",
                        Map.of("reasoning_effort", "low", "reasoning", Map.of("summary", "auto")),
                        Map.of("reasoning", Map.of("summary", "auto"))),
                arguments("text",
                        Map.of("response_format", Map.of("type", "json_object"), "text", Map.of("verbosity", "low")),
                        Map.of("text", Map.of("verbosity", "low"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "input", "stream"})
    void rejectsAnExtraBodyKeyOpikSetsFromTheRequest(String key) {
        var request = withExtraBody(request().build(), Map.of(key, "other"));

        assertRejectedBeforeSending(request,
                "custom_parameters.%s is set by Opik from the request and cannot be replaced".formatted(key));
    }

    @Test
    void rejectsAChatCompletionsKeyWhoseValueItsTypedSettingCannotTake() {
        assertRejectedBeforeSending(withExtraBody(request().build(), Map.of("max_tokens", "lots")),
                "Invalid custom_parameters.max_tokens for the OpenAI Responses API");
    }

    @Test
    void blockingAndStreamingSendOnlyTheTypedSettingsWithoutAnExtraBody() {
        var typedRequest = request().temperature(0.3).maxCompletionTokens(512).build();
        var expected = expectedBodies(Map.of("temperature", 0.3, "max_output_tokens", 512));

        assertThat(send(typedRequest).bodies()).isEqualTo(expected);
        assertThat(send(withExtraBody(typedRequest, Map.of())).bodies()).isEqualTo(expected);
    }

    private void assertRejectedBeforeSending(ChatCompletionRequest request, String message) {
        assertThatThrownBy(() -> provider.generate(request, "ws-1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(message);
        assertThatThrownBy(() -> provider.generateStream(request, "ws-1", chunk -> {
        }, () -> {
        }, error -> {
        }))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(message);
        assertThat(openAi.findAll(postRequestedFor(urlPathEqualTo(RESPONSES_PATH)))).isEmpty();
    }

    private Sent send(ChatCompletionRequest request) {
        openAi.resetRequests();
        var blockingError = catchThrowable(() -> provider.generate(request, "ws-1"));
        List<Throwable> streamingErrors = new CopyOnWriteArrayList<>();
        var closed = new AtomicBoolean();
        provider.generateStream(request, "ws-1", chunk -> {
        }, () -> closed.set(true), streamingErrors::add);
        await().atMost(Duration.ofSeconds(10)).until(() -> closed.get() || !streamingErrors.isEmpty());

        var requests = openAi.findAll(postRequestedFor(urlPathEqualTo(RESPONSES_PATH)));
        assertThat(requests).hasSize(2).allSatisfy(sent -> {
            assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer test-key");
            assertThat(sent.getHeader(CUSTOM_HEADER)).isEqualTo("custom-value");
        });
        var bodiesByStreaming = requests.stream()
                .map(LoggedRequest::getBodyAsString)
                .map(LlmProviderOpenAiResponsesExtraBodyTest::parseStrictly)
                .collect(Collectors.partitioningBy(body -> body.path("stream").asBoolean()));
        assertThat(bodiesByStreaming.get(false)).hasSize(1);
        assertThat(bodiesByStreaming.get(true)).hasSize(1);
        assertThat(blockingError).isNotNull();
        assertThat(streamingErrors).hasSize(1);
        return new Sent(
                List.of(bodiesByStreaming.get(false).getFirst(), bodiesByStreaming.get(true).getFirst()),
                List.<Class<?>>of(blockingError.getClass(), streamingErrors.getFirst().getClass()));
    }

    private static List<ObjectNode> expectedBodies(Map<String, Object> fields) {
        var blocking = (ObjectNode) BASE_BODY.deepCopy().setAll(STRICT_JSON.<ObjectNode>valueToTree(fields));
        return List.of(blocking, blocking.deepCopy().put("stream", true));
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
