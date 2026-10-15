package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.not;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Asserts the body each OpenAI pipeline sends for a stored effort, through the real clients the
 * provider builds for a key's pipeline mode. The stubs reply successfully so a call that sends the
 * right body but then fails on the reply still fails the test.
 */
class OpenAILlmServiceProviderReasoningEffortTest {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String RESPONSES_PATH = "/responses";
    private static final String PIPELINE_MODE_KEY = "openai_pipeline_mode";

    private static final String CHAT_COMPLETION = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-6-sol",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
            """;

    private static final String CHAT_COMPLETION_CHUNK = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-6-sol",
             "choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
            """;

    private static final String COMPLETED_RESPONSE = """
            {"id":"resp_1","object":"response","created_at":1741476542,"status":"completed",
             "error":null,"incomplete_details":null,"instructions":null,"max_output_tokens":null,
             "model":"gpt-6-sol","parallel_tool_calls":true,"previous_response_id":null,
             "reasoning":{"effort":"max","summary":null},"store":true,"temperature":1.0,
             "text":{"format":{"type":"text"}},"tool_choice":"auto","tools":[],"top_p":1.0,
             "truncation":"disabled","user":null,"metadata":{},
             "output":[{"type":"message","id":"msg_1","status":"completed","role":"assistant",
                        "content":[{"type":"output_text","text":"ok","annotations":[]}]}],
             "usage":{"input_tokens":5,"input_tokens_details":{"cached_tokens":0},
                      "output_tokens":1,"output_tokens_details":{"reasoning_tokens":0},
                      "total_tokens":6}}
            """;

    private WireMockServer openAi;
    private OpenAILlmServiceProvider serviceProvider;

    @BeforeEach
    void setUp() {
        openAi = new WireMockServer(0);
        openAi.start();
        stubChatCompletions();
        stubResponses();
        serviceProvider = new OpenAILlmServiceProvider(
                new OpenAIClientGenerator(new LlmProviderClientConfig()),
                Mockito.mock(LlmProviderFactory.class));
    }

    @AfterEach
    void tearDown() {
        openAi.stop();
    }

    @Test
    void chatCompletionsSendsAStoredMaxAsHigh() {
        var service = serviceFor(Map.of(PIPELINE_MODE_KEY, "chat_completions_api"));

        sendBlockingAndStreaming(service, requestWithEffort("max"));

        openAi.verify(2, chatCompletionsRequest()
                .withRequestBody(matchingJsonPath("$.reasoning_effort", equalTo("high"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "low", "high", "xhigh"})
    void chatCompletionsSendsEveryOtherEffortUnchanged(String effort) {
        var service = serviceFor(Map.of(PIPELINE_MODE_KEY, "chat_completions_api"));

        sendBlockingAndStreaming(service, requestWithEffort(effort));

        openAi.verify(2, chatCompletionsRequest()
                .withRequestBody(matchingJsonPath("$.reasoning_effort", equalTo(effort))));
    }

    @Test
    void chatCompletionsSendsNoEffortWhenNoneIsStored() {
        var service = serviceFor(Map.of(PIPELINE_MODE_KEY, "chat_completions_api"));

        sendBlockingAndStreaming(service, requestWithEffort(null));

        openAi.verify(2, chatCompletionsRequest()
                .withRequestBody(not(matchingJsonPath("$.reasoning_effort"))));
    }

    @ParameterizedTest
    @MethodSource("keysWithoutAValidPipelineMode")
    void keyWithoutAValidPipelineModeUsesChatCompletionsAndSendsAStoredMaxAsHigh(
            Map<String, String> configuration) {
        var service = serviceFor(configuration);

        sendBlockingAndStreaming(service, requestWithEffort("max"));

        openAi.verify(2, chatCompletionsRequest()
                .withRequestBody(matchingJsonPath("$.reasoning_effort", equalTo("high"))));
        openAi.verify(0, postRequestedFor(urlPathEqualTo(RESPONSES_PATH)));
    }

    static Stream<Arguments> keysWithoutAValidPipelineMode() {
        return Stream.of(
                Arguments.of(Named.of("no configuration", null)),
                Arguments.of(Named.of("no pipeline mode", Map.of())),
                Arguments.of(Named.of("unknown pipeline mode", Map.of(PIPELINE_MODE_KEY, "batch_api"))));
    }

    @Test
    void responsesApiSendsAStoredMaxUnchanged() {
        var service = serviceFor(Map.of(PIPELINE_MODE_KEY, "responses_api"));

        sendBlockingAndStreaming(service, requestWithEffort("max"));

        openAi.verify(2, postRequestedFor(urlPathEqualTo(RESPONSES_PATH))
                .withRequestBody(matchingJsonPath("$.reasoning.effort", equalTo("max"))));
        openAi.verify(0, chatCompletionsRequest());
    }

    private void stubChatCompletions() {
        openAi.stubFor(post(urlPathEqualTo(CHAT_COMPLETIONS_PATH)).willReturn(okJson(CHAT_COMPLETION)));
        openAi.stubFor(post(urlPathEqualTo(CHAT_COMPLETIONS_PATH))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                .willReturn(eventStream(
                        sseData(CHAT_COMPLETION_CHUNK),
                        "data: [DONE]\n\n")));
    }

    private void stubResponses() {
        var completed = JsonUtils.getJsonNodeFromString(COMPLETED_RESPONSE).toString();
        var inProgress = completed.replace("\"status\":\"completed\"", "\"status\":\"in_progress\"");
        openAi.stubFor(post(urlPathEqualTo(RESPONSES_PATH)).willReturn(okJson(completed)));
        openAi.stubFor(post(urlPathEqualTo(RESPONSES_PATH))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                .willReturn(eventStream(
                        sseEvent("response.created",
                                "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":"
                                        + inProgress + "}"),
                        sseEvent("response.output_text.delta",
                                "{\"type\":\"response.output_text.delta\",\"sequence_number\":1,"
                                        + "\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,"
                                        + "\"delta\":\"ok\",\"logprobs\":[]}"),
                        sseEvent("response.completed",
                                "{\"type\":\"response.completed\",\"sequence_number\":2,\"response\":"
                                        + completed + "}"))));
    }

    private static ResponseDefinitionBuilder eventStream(String... events) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(String.join("", events));
    }

    private static String sseData(String json) {
        return "data: " + JsonUtils.getJsonNodeFromString(json) + "\n\n";
    }

    private static String sseEvent(String event, String data) {
        return "event: " + event + "\ndata: " + data + "\n\n";
    }

    private LlmProviderService serviceFor(Map<String, String> configuration) {
        return serviceProvider.getService(LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl(openAi.baseUrl())
                .configuration(configuration)
                .build());
    }

    private static void sendBlockingAndStreaming(LlmProviderService service, ChatCompletionRequest request) {
        assertThat(service.generate(request, "ws-1").content()).isEqualTo("ok");

        List<Throwable> errors = new CopyOnWriteArrayList<>();
        var closed = new AtomicBoolean();
        service.generateStream(request, "ws-1", chunk -> {
        }, () -> closed.set(true), errors::add);

        await().atMost(Duration.ofSeconds(10)).until(() -> closed.get() || !errors.isEmpty());
        assertThat(errors).isEmpty();
        assertThat(closed).isTrue();
    }

    private static RequestPatternBuilder chatCompletionsRequest() {
        return postRequestedFor(urlPathEqualTo(CHAT_COMPLETIONS_PATH));
    }

    private static ChatCompletionRequest requestWithEffort(String effort) {
        return ChatCompletionRequest.builder()
                .model("gpt-6-sol")
                .addUserMessage("hi")
                .reasoningEffort(effort)
                .build();
    }
}
