package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.not;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/**
 * Asserts the body each OpenAI pipeline sends for a stored effort, through the real clients the
 * provider builds for a key's pipeline mode. The stub answers 400 because only the request matters.
 */
class OpenAILlmServiceProviderReasoningEffortTest {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String RESPONSES_PATH = "/responses";

    private WireMockServer openAi;
    private OpenAILlmServiceProvider serviceProvider;

    @BeforeEach
    void setUp() {
        openAi = new WireMockServer(0);
        openAi.start();
        openAi.stubFor(post(anyUrl()).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"stub\",\"type\":\"invalid_request_error\"}}")));
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
        var service = serviceFor("chat_completions_api");

        sendBlockingAndStreaming(service, requestWithEffort("max"));

        awaitRequests(2, postRequestedFor(urlPathEqualTo(CHAT_COMPLETIONS_PATH))
                .withRequestBody(matchingJsonPath("$.reasoning_effort", equalTo("high"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "low", "high", "xhigh"})
    void chatCompletionsSendsEveryOtherEffortUnchanged(String effort) {
        var service = serviceFor("chat_completions_api");

        sendBlockingAndStreaming(service, requestWithEffort(effort));

        awaitRequests(2, postRequestedFor(urlPathEqualTo(CHAT_COMPLETIONS_PATH))
                .withRequestBody(matchingJsonPath("$.reasoning_effort", equalTo(effort))));
    }

    @Test
    void chatCompletionsSendsNoEffortWhenNoneIsStored() {
        var service = serviceFor("chat_completions_api");

        sendBlockingAndStreaming(service, requestWithEffort(null));

        awaitRequests(2, postRequestedFor(urlPathEqualTo(CHAT_COMPLETIONS_PATH))
                .withRequestBody(not(matchingJsonPath("$.reasoning_effort"))));
    }

    @Test
    void responsesApiSendsAStoredMaxUnchanged() {
        var service = serviceFor("responses_api");

        sendBlockingAndStreaming(service, requestWithEffort("max"));

        awaitRequests(2, postRequestedFor(urlPathEqualTo(RESPONSES_PATH))
                .withRequestBody(matchingJsonPath("$.reasoning.effort", equalTo("max"))));
    }

    private LlmProviderService serviceFor(String pipelineMode) {
        return serviceProvider.getService(LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl(openAi.baseUrl())
                .configuration(Map.of("openai_pipeline_mode", pipelineMode))
                .build());
    }

    private static void sendBlockingAndStreaming(LlmProviderService service, ChatCompletionRequest request) {
        catchThrowable(() -> service.generate(request, "ws-1"));
        service.generateStream(request, "ws-1", chunk -> {
        }, () -> {
        }, error -> {
        });
    }

    private void awaitRequests(int count, RequestPatternBuilder pattern) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> openAi.verify(count, pattern));
    }

    private static ChatCompletionRequest requestWithEffort(String effort) {
        return ChatCompletionRequest.builder()
                .model("gpt-6-sol")
                .addUserMessage("hi")
                .reasoningEffort(effort)
                .build();
    }
}
