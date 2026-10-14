package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Feeds the real langchain4j Responses models an OpenAI-shaped reply, so the usage asserted is what
 * a client of the proxy would receive.
 */
class LlmProviderOpenAiResponsesUsageTest {

    private static final String RESPONSES_PATH = "/responses";

    private static final String COMPLETED_RESPONSE = """
            {"id":"resp_1","object":"response","created_at":1741476542,"status":"completed",
             "error":null,"incomplete_details":null,"instructions":null,"max_output_tokens":null,
             "model":"gpt-5.5","parallel_tool_calls":true,"previous_response_id":null,
             "reasoning":{"effort":"xhigh","summary":null},"store":true,"temperature":1.0,
             "text":{"format":{"type":"text"}},"tool_choice":"auto","tools":[],"top_p":1.0,
             "truncation":"disabled","user":null,"metadata":{},
             "output":[
               {"type":"reasoning","id":"rs_1","summary":[]},
               {"type":"message","id":"msg_1","status":"completed","role":"assistant",
                "content":[{"type":"output_text","text":"62","annotations":[]}]}],
             "usage":{"input_tokens":25,"input_tokens_details":{"cached_tokens":0},
                      "output_tokens":90,"output_tokens_details":{"reasoning_tokens":85},
                      "total_tokens":115}}
            """;

    private static final String EXPECTED_USAGE = """
            {"prompt_tokens":25,"completion_tokens":90,"total_tokens":115,
             "prompt_tokens_details":{"cached_tokens":0},
             "completion_tokens_details":{"reasoning_tokens":85}}
            """;

    private WireMockServer openAi;
    private LlmProviderOpenAiResponses provider;

    @BeforeEach
    void setUp() {
        openAi = new WireMockServer(0);
        openAi.start();
        provider = new LlmProviderOpenAiResponses(
                new OpenAIClientGenerator(new LlmProviderClientConfig()),
                LlmProviderClientApiConfig.builder()
                        .apiKey("test-key")
                        .baseUrl(openAi.baseUrl())
                        .build());
    }

    @AfterEach
    void tearDown() {
        openAi.stop();
    }

    @Test
    void blockingReplyCarriesTheReasoningAndCachedTokens() {
        openAi.stubFor(post(urlPathEqualTo(RESPONSES_PATH)).willReturn(okJson(COMPLETED_RESPONSE)));

        var response = provider.generate(request(), "ws-1");

        assertThat(usageOf(response)).isEqualTo(JsonUtils.getJsonNodeFromString(EXPECTED_USAGE));
    }

    @Test
    void streamingFinalChunkCarriesTheReasoningAndCachedTokens() {
        var completed = JsonUtils.getJsonNodeFromString(COMPLETED_RESPONSE).toString();
        var inProgress = completed.replace("\"status\":\"completed\"", "\"status\":\"in_progress\"");
        var events = String.join("",
                sse("response.created",
                        "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":" + inProgress + "}"),
                sse("response.output_text.delta",
                        "{\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"item_id\":\"msg_1\","
                                + "\"output_index\":1,\"content_index\":0,\"delta\":\"62\",\"logprobs\":[]}"),
                sse("response.completed",
                        "{\"type\":\"response.completed\",\"sequence_number\":2,\"response\":" + completed + "}"));
        openAi.stubFor(post(urlPathEqualTo(RESPONSES_PATH))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(events)));
        List<ChatCompletionResponse> chunks = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        var closed = new AtomicBoolean();

        provider.generateStream(request(), "ws-1", chunks::add, () -> closed.set(true), errors::add);

        await().atMost(Duration.ofSeconds(10)).until(() -> closed.get() || !errors.isEmpty());
        assertThat(errors).isEmpty();
        assertThat(usageOf(chunks.getLast())).isEqualTo(JsonUtils.getJsonNodeFromString(EXPECTED_USAGE));
    }

    private static String sse(String event, String data) {
        return "event: " + event + "\ndata: " + data + "\n\n";
    }

    private static ChatCompletionRequest request() {
        return ChatCompletionRequest.builder()
                .model("gpt-5.5")
                .addUserMessage("How many prime numbers are there between 1 and 300?")
                .reasoningEffort("xhigh")
                .build();
    }

    private static JsonNode usageOf(ChatCompletionResponse response) {
        return JsonUtils.valueToTree(response.usage());
    }
}
