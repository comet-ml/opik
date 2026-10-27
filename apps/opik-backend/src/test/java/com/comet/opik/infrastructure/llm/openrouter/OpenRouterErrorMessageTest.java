package com.comet.opik.infrastructure.llm.openrouter;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenRouterErrorMessageTest {

    private static final String COMPLETIONS_PATH = "/api/v1/chat/completions";
    private static final String PROVIDER_ERROR = """
            {"error":{"message":"Provider returned error","code":400,"metadata":{"raw":"{\\n  \\"error\\": {\\n    \\"message\\": \\"This model requires that either input content or output modality contain audio.\\",\\n    \\"type\\": \\"invalid_request_error\\",\\n    \\"param\\": \\"model\\",\\n    \\"code\\": \\"invalid_value\\"\\n  }\\n}","provider_name":"OpenAI","is_byok":false,"provider_error_code":"invalid_value"}}}""";
    private static final String PROVIDER_ERROR_MESSAGE = "OpenAI: This model requires that either input content or output modality contain audio.";
    private static final String IN_STREAM_ERROR = """
            data: {"id":"gen-1","object":"chat.completion.chunk","created":0,"model":"openai/gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"},"finish_reason":null}]}

            data: {"id":"gen-1","object":"chat.completion.chunk","created":0,"model":"openai/gpt-4o","provider":"OpenAI","error":{"code":429,"message":"Rate limit exceeded","metadata":{"error_type":"rate_limit_exceeded"}},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}

            data: [DONE]

            """;

    private WireMockServer wireMock;
    private LlmProviderService provider;

    @BeforeAll
    void setUpAll() throws IOException {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        var clientConfig = new LlmProviderClientConfig();
        clientConfig.setLogRequests(false);
        clientConfig.setLogResponses(false);
        clientConfig.setOpenRouterUrl(wireMock.baseUrl() + "/api/v1");

        var clientGenerator = new OpenRouterModule().clientGenerator(clientConfig,
                LlmTestClients.RESPONSES_API_HTTP_CLIENT);
        provider = new OpenRouterLlmServiceProvider(clientGenerator, mock(LlmProviderFactory.class))
                .getService(LlmProviderClientApiConfig.builder().apiKey("test-key").build());
    }

    @AfterAll
    void tearDownAll() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
    }

    static Stream<Arguments> openRouterErrors() {
        return Stream.of(
                Arguments.of("upstream JSON, pretty-printed", PROVIDER_ERROR, 400, PROVIDER_ERROR_MESSAGE),
                Arguments.of("upstream JSON, Google shape",
                        """
                                {"error":{"message":"Provider returned error","code":400,"metadata":{"raw":"{\\n  \\"error\\": {\\n    \\"code\\": 400,\\n    \\"message\\": \\"Request contains an invalid argument.\\",\\n    \\"status\\": \\"INVALID_ARGUMENT\\"\\n  }\\n}\\n","provider_name":"Google AI Studio","is_byok":false,"provider_error_code":"400"}}}""",
                        400, "Google AI Studio: Request contains an invalid argument."),
                Arguments.of("upstream JSON, compact",
                        """
                                {"error":{"message":"Provider returned error","code":400,"metadata":{"raw":"{\\"error\\":{\\"message\\":\\"Expected <code>...</code> and <update>...</update> tags.\\",\\"type\\":\\"invalid_request_error\\",\\"param\\":null,\\"code\\":null}}","provider_name":"Relace","is_byok":false}}}""",
                        400, "Provider returned error"),
                Arguments.of("upstream JSON, retired model",
                        """
                                {"error":{"message":"Provider returned error","code":404,"metadata":{"raw":"{\\n    \\"error\\": {\\n        \\"message\\": \\"The model `gpt-3.5-turbo-instruct` has been deprecated, learn more here: https://platform.openai.com/docs/deprecations\\",\\n        \\"type\\": \\"invalid_request_error\\",\\n        \\"param\\": null,\\n        \\"code\\": \\"model_not_found\\"\\n    }\\n}\\n","provider_name":"OpenAI","is_byok":false,"provider_error_code":"model_not_found"}}}""",
                        404,
                        "OpenAI: The model `gpt-3.5-turbo-instruct` has been deprecated, learn more here: https://platform.openai.com/docs/deprecations"),
                Arguments.of("upstream JSON, top-level message",
                        """
                                {"error":{"message":"Provider returned error","code":404,"metadata":{"raw":"{\\"message\\":\\"This model version has reached the end of its life. Please refer to the AWS documentation for more details.\\"}","provider_name":"Amazon Bedrock","is_byok":false}}}""",
                        404, "Provider returned error"),
                Arguments.of("upstream plain text, rate limit",
                        """
                                {"error":{"message":"Provider returned error","code":429,"metadata":{"raw":"google/gemma-4-31b-it:free is temporarily rate-limited upstream. Please retry shortly, or add your own key to accumulate your rate limits: https://openrouter.ai/settings/integrations","provider_name":"Google AI Studio","is_byok":false,"provider_error_code":"429","limit_source":"upstream_provider_shared_pool","remedy_hint":"Retry shortly, add your own provider key (https://openrouter.ai/settings/integrations), or route to another provider with provider routing: https://openrouter.ai/docs/features/provider-routing"}}}""",
                        429, "Provider returned error"),
                Arguments.of("upstream JSON, error as a string",
                        """
                                {"error":{"message":"Provider returned error","code":400,"metadata":{"raw":"{\\"error\\":\\"  Model   not found  \\"}","provider_name":"DeepInfra"}}}""",
                        400, "Provider returned error"),
                Arguments.of("upstream JSON, detail shape",
                        """
                                {"error":{"message":"Provider returned error","code":422,"metadata":{"raw":"{\\"detail\\":\\"Input should be a valid list\\"}","provider_name":"Chutes"}}}""",
                        422, "Provider returned error"),
                Arguments.of("upstream JSON, Anthropic shape",
                        """
                                {"error":{"message":"Provider returned error","code":502,"metadata":{"raw":"{\\"type\\":\\"error\\",\\"error\\":{\\"type\\":\\"overloaded_error\\",\\"message\\":\\"Overloaded\\"}}","provider_name":"Anthropic"}}}""",
                        502, "Anthropic: Overloaded"),
                Arguments.of("upstream JSON, undocumented OpenAI-style type",
                        """
                                {"error":{"message":"Provider returned error","code":400,"metadata":{"raw":"{\\"error\\":{\\"message\\":\\"blocked by gateway\\",\\"type\\":\\"gateway_error\\"}}","provider_name":"Together"}}}""",
                        400, "Provider returned error"),
                Arguments.of("upstream JSON, undocumented Google status",
                        """
                                {"error":{"message":"Provider returned error","code":429,"metadata":{"raw":"{\\"error\\":{\\"code\\":429,\\"message\\":\\"Slow down\\",\\"status\\":\\"THROTTLED\\"}}","provider_name":"Google AI Studio"}}}""",
                        429, "Provider returned error"),
                Arguments.of("upstream JSON, malformed",
                        """
                                {"error":{"message":"Provider returned error","code":502,"metadata":{"raw":"{\\"error\\": {\\"message\\": \\"upstream conn","provider_name":"Together"}}}""",
                        502, "Provider returned error"),
                Arguments.of("no provider name", """
                        {"error":{"message":"Provider returned error","code":400,"metadata":{"raw":"Bad request"}}}""",
                        400, "Provider returned error"),
                Arguments.of("no provider name, other envelope message", """
                        {"error":{"message":"Gateway rejected request","code":400,"metadata":{"raw":"Bad request"}}}""",
                        400, "Gateway rejected request"),
                Arguments.of("no provider name, blank envelope message", """
                        {"error":{"message":" ","code":400,"metadata":{"raw":"Bad request"}}}""",
                        400, " "),
                Arguments.of("no upstream body, no endpoints",
                        """
                                {"error":{"message":"No endpoints found for openai/gpt-5.2-chat. Every candidate endpoint was removed during routing: Add BYOK Endpoints removed azure (the Azure endpoint is BYOK-only and you have no Azure key that can serve openai/gpt-5.2-chat-20251211).","code":404,"metadata":{"routing_funnel":[{"step":"Initial Endpoints","endpoint_count":1},{"step":"Add BYOK Endpoints","endpoint_count":0,"reason":"the Azure endpoint is BYOK-only and you have no Azure key that can serve openai/gpt-5.2-chat-20251211"}],"failed_routing_step":"Add BYOK Endpoints"}}}""",
                        404,
                        "No endpoints found for openai/gpt-5.2-chat. Every candidate endpoint was removed during routing: Add BYOK Endpoints removed azure (the Azure endpoint is BYOK-only and you have no Azure key that can serve openai/gpt-5.2-chat-20251211)."),
                Arguments.of("no upstream body, out of credits",
                        """
                                {"error":{"message":"This request requires more credits, or fewer max_tokens. You requested up to 65536 tokens, but can only afford 11899. To increase, visit https://openrouter.ai/workspaces/default/keys/<key-id> and adjust the key's monthly limit","code":402,"metadata":{"limit_source":"openrouter_credits","remedy_hint":"Add credits at https://openrouter.ai/settings/credits, or lower max_tokens / prompt size to fit your remaining balance.","provider_name":null}}}""",
                        402,
                        "This request requires more credits, or fewer max_tokens. You requested up to 65536 tokens, but can only afford 11899. To increase, visit https://openrouter.ai/workspaces/default/keys/<key-id> and adjust the key's monthly limit"),
                Arguments.of("no metadata", """
                        {"error":{"code":429,"message":"Rate limited by upstream"}}""",
                        429, "Rate limited by upstream"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("openRouterErrors")
    void getLlmProviderErrorShowsTheUpstreamReason(String shape, String body, int code, String expectedMessage) {
        var expectedError = new ErrorMessage(code, expectedMessage);

        var actualError = provider.getLlmProviderError(new HttpException(code, body));

        assertThat(actualError).contains(expectedError);
    }

    @Test
    void getLlmProviderErrorCapsALongUpstreamReason() {
        var body = JsonUtils.writeValueAsString(Map.of("error", Map.of(
                "message", "Provider returned error",
                "code", 502,
                "metadata",
                Map.of("raw", JsonUtils.writeValueAsString(Map.of("error", Map.of("type", "server_error",
                        "message", "Bad gateway ".repeat(100)))), "provider_name", "Groq"))));

        var actualMessage = provider.getLlmProviderError(new HttpException(502, body)).orElseThrow().getMessage();

        assertThat(actualMessage).startsWith("Groq: Bad gateway").endsWith("...").hasSize("Groq: ".length() + 500);
    }

    @Test
    void getLlmProviderErrorHidesTheTextOfAnUndocumentedCode() {
        var body = JsonUtils.writeValueAsString(Map.of("error", Map.of(
                "message", UUID.randomUUID().toString(), "code", 418)));
        var expectedError = new ErrorMessage(418, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);

        var actualError = provider.getLlmProviderError(new HttpException(418, body));

        assertThat(actualError).contains(expectedError);
    }

    @Test
    void generateWhenProviderFailsReportsTheUpstreamReason() {
        stubProviderError();

        var failure = catchThrowable(() -> provider.generate(request(false), "workspace-id"));

        assertThat(provider.getLlmProviderError(failure)).contains(new ErrorMessage(400, PROVIDER_ERROR_MESSAGE));
    }

    @Test
    void generateStreamWhenProviderFailsReportsTheUpstreamReason() throws Exception {
        stubProviderError();
        var failure = new CompletableFuture<Throwable>();

        provider.generateStream(request(true), "workspace-id", response -> {
        }, () -> failure.completeExceptionally(new AssertionError("stream closed without an error")),
                failure::complete);

        assertThat(provider.getLlmProviderError(failure.get(10, TimeUnit.SECONDS)))
                .contains(new ErrorMessage(400, PROVIDER_ERROR_MESSAGE));
    }

    @Test
    void generateStreamReportsAnErrorSentInsideTheStream() throws Exception {
        wireMock.stubFor(post(urlEqualTo(COMPLETIONS_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(IN_STREAM_ERROR)));
        List<ChatCompletionResponse> messages = new CopyOnWriteArrayList<>();
        var closed = new AtomicBoolean();
        var failure = new CompletableFuture<Throwable>();

        provider.generateStream(request(true), "workspace-id", messages::add, () -> {
            closed.set(true);
            failure.completeExceptionally(new AssertionError("stream closed without an error"));
        }, failure::complete);

        assertThat(provider.getLlmProviderError(failure.get(10, TimeUnit.SECONDS)))
                .contains(new ErrorMessage(429, "Rate limit exceeded"));
        assertThat(messages).extracting(message -> message.choices().getFirst().delta().content())
                .containsExactly("Hel");
        Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilFalse(closed);
    }

    private void stubProviderError() {
        wireMock.stubFor(post(urlEqualTo(COMPLETIONS_PATH)).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody(PROVIDER_ERROR)));
    }

    private ChatCompletionRequest request(boolean stream) {
        return JsonUtils.readValue("""
                {"model": "openai/gpt-audio", "messages": [{"role": "user", "content": "Say ok"}], "stream": %s}
                """.formatted(stream), ChatCompletionRequest.class);
    }
}
