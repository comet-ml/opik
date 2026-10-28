package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.llm.ProviderErrorMessageValidator;
import com.comet.opik.infrastructure.llm.GeminiMaxOutputTokens;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.comet.opik.infrastructure.llm.StreamingResponseLogger;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Gemini streaming")
class LlmProviderGeminiTest {

    private static final String MODEL = "gemini-2.5-flash";
    private static final String STREAM_GENERATE_CONTENT_PATH = ".*:streamGenerateContent";

    private static final String ANSWER_CHUNK = """
            {
              "candidates": [
                {
                  "content": {"role": "model", "parts": [{"text": "Hello"}]},
                  "finishReason": "STOP"
                }
              ],
              "usageMetadata": {"promptTokenCount": 3, "candidatesTokenCount": 1, "totalTokenCount": 40}
            }
            """;

    private final WireMockUtils.WireMockRuntime wireMock = WireMockUtils.startWireMock();

    @AfterAll
    void tearDown() {
        wireMock.server().stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.server().resetAll();
    }

    /** The summary line of Gemini's quota error, followed in its message by the quota's specifics. */
    private static final String QUOTA_SUMMARY = """
            You exceeded your current quota, please check your plan and billing details. For more information on \
            this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits.""";

    private static String quotaErrorBody() {
        var message = "%s\n* Quota exceeded for metric: generate_content_free_tier_requests, limit: %d\nPlease retry in %ds."
                .formatted(QUOTA_SUMMARY, RandomUtils.secure().randomInt(1, 100),
                        RandomUtils.secure().randomInt(1, 60));
        return JsonUtils.writeValueAsString(
                Map.of("error", Map.of("code", 429, "message", message, "status", "RESOURCE_EXHAUSTED")));
    }

    /** As ChatCompletionService passes it on: parsed by the provider, then validated. */
    private static ErrorMessage callerError(Throwable failure) {
        var provider = new LlmProviderGemini(mock(GeminiClientGenerator.class),
                LlmProviderClientApiConfig.builder().apiKey("test-api-key").build());
        var validator = new ProviderErrorMessageValidator(TestConfigUtils.loadConfigTest().getLlmProviderClient());
        return provider.getLlmProviderError(failure).map(validator::validate).orElseThrow();
    }

    @Test
    @DisplayName("a documented quota error keeps its summary line, the specifics after it dropped")
    void quotaErrorKeepsItsSummaryLine() {
        wireMock.server().stubFor(post(urlPathMatching(".*:generateContent"))
                .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                        .withBody(quotaErrorBody())));
        var model = GoogleAiGeminiChatModel.builder()
                .baseUrl("http://localhost:%d/v1beta".formatted(wireMock.server().port()))
                .apiKey("test-api-key")
                .httpClientBuilder(LlmTestClients.LLM_HTTP_CLIENT_BUILDERS.builder())
                .modelName(MODEL)
                .maxRetries(0)
                .build();
        var expectedError = new ErrorMessage(429, QUOTA_SUMMARY, "RESOURCE_EXHAUSTED");

        var failure = catchThrowable(() -> model.chat(List.of(UserMessage.from("hello"))));

        assertThat(callerError(failure)).isEqualTo(expectedError);
    }

    @Test
    @DisplayName("a documented quota error on a stream keeps its summary line")
    void streamedQuotaErrorKeepsItsSummaryLine() throws Exception {
        wireMock.server().stubFor(post(urlPathMatching(STREAM_GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                        .withBody(quotaErrorBody())));
        var expectedErrors = List.of(new ErrorMessage(429, QUOTA_SUMMARY, "RESOURCE_EXHAUSTED"));

        var outcome = stream(request(null, null, null));

        assertThat(outcome.errors().stream().map(LlmProviderGeminiTest::callerError).toList())
                .isEqualTo(expectedErrors);
    }

    @Test
    @DisplayName("an empty stream under an output limit ends with finish reason length")
    void emptyStreamUnderALimitEndsWithLength() throws Exception {
        stubStream("");

        var outcome = stream(request(20, null, null));

        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.content()).isEmpty();
        assertThat(outcome.lastChoice().finishReason()).isEqualTo("length");
        assertThat(outcome.messages().getLast().usage()).isNull();
    }

    @Test
    @DisplayName("an answered stream keeps Google's own finish reason")
    void answeredStreamKeepsItsFinishReason() throws Exception {
        stubStream("data: " + ANSWER_CHUNK.replace("\n", " ") + "\n\n");

        var outcome = stream(request(20, null, null));

        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.content()).isEqualTo("Hello");
        assertThat(outcome.lastChoice().finishReason()).isEqualTo("stop");
    }

    private static Stream<Arguments> finishReasonCases() {
        var empty = response(null, null, null);
        return Stream.of(
                Arguments.of("empty under a limit", 20, null, null, empty, FinishReason.LENGTH),
                Arguments.of("empty under a limit, thinking level auto", 20, null,
                        Map.of("thinking", Map.of("level", "auto")), empty, FinishReason.LENGTH),
                Arguments.of("empty under a max_tokens limit", null, 20, null, empty, FinishReason.LENGTH),
                Arguments.of("empty under max_tokens when max_completion_tokens is 0", 0, 20, null, empty,
                        FinishReason.LENGTH),
                Arguments.of("empty without a limit", null, null, null, empty, null),
                Arguments.of("empty with a limit of 0", 0, null, null, empty, null),
                Arguments.of("empty with both limits 0", 0, 0, null, empty, null),
                Arguments.of("empty with thinking off by a zero budget", 20, null,
                        Map.of("thinking", Map.of("budget_tokens", 0)), empty, null),
                Arguments.of("empty with thinking level off", 20, null,
                        Map.of("thinking", Map.of("level", "off")), empty, null),
                Arguments.of("text without a finish reason", 20, null, null, response("Hello", null, null), null),
                Arguments.of("usage without a finish reason", 20, null, null,
                        response(null, null, new TokenUsage(22, 0, 38)), null),
                Arguments.of("Google's own stop", 20, null, null,
                        response("Hello", FinishReason.STOP, new TokenUsage(3, 1, 40)), FinishReason.STOP),
                Arguments.of("Google's own length", 20, null, null,
                        response(null, FinishReason.LENGTH, new TokenUsage(22, 0, 38)), FinishReason.LENGTH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("finishReasonCases")
    @DisplayName("keeps each finish reason, except an empty stream under an output limit with thinking not turned off, which gets length")
    void addsLengthOnlyToAnEmptyStreamUnderALimit(String name, Integer maxCompletionTokens, Integer maxTokens,
            Map<String, Object> customParameters, ChatResponse response, FinishReason expected) {
        var result = LlmProviderGemini.withCutOffWhileThinking(
                request(maxCompletionTokens, maxTokens, customParameters), response);

        assertThat(result.finishReason()).as("[%s] finish reason", name).isEqualTo(expected);
        assertThat(result.aiMessage()).as("[%s] message", name).isEqualTo(response.aiMessage());
        assertThat(result.tokenUsage()).as("[%s] token usage", name).isEqualTo(response.tokenUsage());
    }

    private static ChatResponse response(String text, FinishReason finishReason, TokenUsage tokenUsage) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.builder().text(text).build())
                .finishReason(finishReason)
                .tokenUsage(tokenUsage)
                .build();
    }

    private static ChatCompletionRequest request(Integer maxCompletionTokens, Integer maxTokens,
            Map<String, Object> customParameters) {
        return ChatCompletionRequest.builder()
                .model(MODEL)
                .addUserMessage("hello")
                .maxCompletionTokens(maxCompletionTokens)
                .maxTokens(maxTokens)
                .customParameters(customParameters)
                .build();
    }

    private void stubStream(String body) {
        wireMock.server().stubFor(post(urlPathMatching(STREAM_GENERATE_CONTENT_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(body)));
    }

    private record StreamOutcome(List<ChatCompletionResponse> messages, List<Throwable> errors) {

        String content() {
            return messages.stream()
                    .map(message -> message.choices().getFirst().delta().content())
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining());
        }

        ChatCompletionChoice lastChoice() {
            return messages.getLast().choices().getFirst();
        }
    }

    private StreamOutcome stream(ChatCompletionRequest request) throws Exception {
        var messages = new CopyOnWriteArrayList<ChatCompletionResponse>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        var terminal = new CountDownLatch(1);
        var delegate = new ChunkedResponseHandler(messages::add, terminal::countDown, error -> {
            errors.add(error);
            terminal.countDown();
        }, MODEL);

        GoogleAiGeminiStreamingChatModel.builder()
                .baseUrl("http://localhost:%d/v1beta".formatted(wireMock.server().port()))
                .apiKey("test-api-key")
                .httpClientBuilder(LlmTestClients.LLM_HTTP_CLIENT_BUILDERS.builder())
                .modelName(MODEL)
                .maxOutputTokens(GeminiMaxOutputTokens.firstPositive(request).orElse(null))
                .returnThinking(false)
                .build()
                .chat(List.of(UserMessage.from("hello")),
                        LlmProviderGemini.streamHandler(request, delegate,
                                new StreamingResponseLogger("model=" + MODEL, MODEL)));

        assertThat(terminal.await(10, TimeUnit.SECONDS)).isTrue();
        return new StreamOutcome(messages, errors);
    }
}
