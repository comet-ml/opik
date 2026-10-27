package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.resources.utils.LogCaptureUtils;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * With request logging on, a provider's header values are still sent but never logged, as LangChain4j's request log
 * would print any header whose name it doesn't take for a credential.
 */
class OpenAIProviderHeadersLoggingTest {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private static final String CHAT_COMPLETION = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-4o",\
            "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""";

    private static final String CHAT_COMPLETION_CHUNK = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-4o",\
            "choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""";

    private WireMockServer openAi;

    @BeforeEach
    void setUp() {
        openAi = new WireMockServer(0);
        openAi.start();
        openAi.stubFor(post(urlPathEqualTo(CHAT_COMPLETIONS_PATH)).willReturn(okJson(CHAT_COMPLETION)));
        openAi.stubFor(post(urlPathEqualTo(CHAT_COMPLETIONS_PATH))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("data: %s\n\ndata: [DONE]\n\n".formatted(CHAT_COMPLETION_CHUNK))));
    }

    @AfterEach
    void tearDown() {
        openAi.stop();
    }

    @Test
    @DisplayName("a provider's header values are sent but not logged, with request logging on")
    void headerValuesAreSentButNotLogged() {
        var clientConfig = TestConfigUtils.loadConfigTest().getLlmProviderClient();
        clientConfig.setLogRequests(true);
        var serviceProvider = new OpenAILlmServiceProvider(new OpenAIClientGenerator(clientConfig,
                LlmTestClients.RESPONSES_API_HTTP_CLIENT, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS),
                Mockito.mock(LlmProviderFactory.class));
        var headerName = "X-Route-%s".formatted(RandomStringUtils.secure().nextAlphabetic(8));
        var headerValue = "marker-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));
        var service = serviceProvider.getService(LlmProviderClientApiConfig.builder()
                .apiKey(RandomStringUtils.secure().nextAlphanumeric(20))
                .baseUrl(openAi.baseUrl())
                .headers(Map.of(headerName, headerValue))
                .build());
        var request = ChatCompletionRequest.builder().model("gpt-4o").addUserMessage("hi").build();
        var expectedContent = "ok";
        var actualContents = new CopyOnWriteArrayList<String>();
        var actualErrors = new CopyOnWriteArrayList<Throwable>();
        var closed = new AtomicBoolean();

        var actualEvents = LogCaptureUtils.logEventsOf(() -> {
            actualContents.add(service.generate(request, "ws-1").content());
            service.generateStream(request, "ws-1", chunk -> {
            }, () -> closed.set(true), actualErrors::add);
            await().atMost(Duration.ofSeconds(10)).until(() -> closed.get() || !actualErrors.isEmpty());
        });

        assertThat(actualErrors).isEmpty();
        assertThat(actualContents).containsExactly(expectedContent);
        openAi.verify(2, postRequestedFor(urlPathEqualTo(CHAT_COMPLETIONS_PATH))
                .withHeader(headerName, equalTo(headerValue)));
        // the request log is on, and so is written, but without the value
        assertThat(actualEvents).anyMatch(event -> event.contains("HTTP request"))
                .noneMatch(event -> event.contains(headerValue));
    }

    /**
     * A row stored before saving refused them may hold a header without a value, or names differing only in case: the
     * call still goes out, without the first, and with one value for the others, the one whose name sorts last.
     */
    @Test
    @DisplayName("a stored header without a value is left out, and names differing only in case send one value")
    void storedHeadersThatCannotBeSentDoNotBreakTheCall() {
        var serviceProvider = new OpenAILlmServiceProvider(new OpenAIClientGenerator(
                TestConfigUtils.loadConfigTest().getLlmProviderClient(), LlmTestClients.RESPONSES_API_HTTP_CLIENT,
                LlmTestClients.LLM_HTTP_CLIENT_BUILDERS), Mockito.mock(LlmProviderFactory.class));
        var emptyHeaderName = "X-Empty-%s".formatted(RandomStringUtils.secure().nextAlphabetic(8));
        var upperCaseValue = "marker-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));
        var lowerCaseValue = "marker-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));
        var headers = new HashMap<String, String>();
        headers.put(emptyHeaderName, null);
        headers.put("X-Route", upperCaseValue);
        headers.put("x-route", lowerCaseValue);
        var config = LlmProviderClientApiConfig.builder()
                .apiKey(RandomStringUtils.secure().nextAlphanumeric(20))
                .baseUrl(openAi.baseUrl())
                .headers(headers)
                .build();
        var request = ChatCompletionRequest.builder().model("gpt-4o").addUserMessage("hi").build();
        var expectedContent = "ok";
        var expectedRouteValues = List.of(lowerCaseValue);
        var actualContents = new ArrayList<String>();

        // the client is built, and its headers checked, along with the service
        var actualEvents = LogCaptureUtils.logEventsOf(() -> actualContents.add(
                serviceProvider.getService(config).generate(request, "ws-1").content()));

        assertThat(actualContents).containsExactly(expectedContent);
        var actualRequest = openAi.findAll(postRequestedFor(urlPathEqualTo(CHAT_COMPLETIONS_PATH))).getFirst();
        assertThat(actualRequest.getHeaders().getHeader("X-Route").values()).isEqualTo(expectedRouteValues);
        assertThat(actualRequest.containsHeader(emptyHeaderName)).isFalse();
        assertThat(actualEvents).anyMatch(event -> event.contains(emptyHeaderName))
                .noneMatch(event -> event.contains(upperCaseValue) || event.contains(lowerCaseValue));
    }
}
