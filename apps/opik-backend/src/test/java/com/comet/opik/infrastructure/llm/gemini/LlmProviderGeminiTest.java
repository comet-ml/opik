package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.llm.StreamingResponseLogger;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
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

    @Test
    @DisplayName("an empty stream under an output limit ends with finish reason length")
    void emptyStreamUnderALimitEndsWithLength() throws Exception {
        stubStream("");

        var outcome = stream(request(20, null));

        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.content()).isEmpty();
        assertThat(outcome.lastChoice().finishReason()).isEqualTo("length");
        assertThat(outcome.messages().getLast().usage()).isNull();
    }

    @Test
    @DisplayName("an answered stream keeps Google's own finish reason")
    void answeredStreamKeepsItsFinishReason() throws Exception {
        stubStream("data: " + ANSWER_CHUNK.replace("\n", " ") + "\n\n");

        var outcome = stream(request(20, null));

        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.content()).isEqualTo("Hello");
        assertThat(outcome.lastChoice().finishReason()).isEqualTo("stop");
    }

    private static Stream<Arguments> finishReasonCases() {
        var empty = response(null, null, null);
        return Stream.of(
                Arguments.of("empty under a limit", 20, null, empty, FinishReason.LENGTH),
                Arguments.of("empty under a limit, thinking level auto", 20,
                        Map.of("thinking", Map.of("level", "auto")), empty, FinishReason.LENGTH),
                Arguments.of("empty without a limit", null, null, empty, null),
                Arguments.of("empty with a limit of 0", 0, null, empty, null),
                Arguments.of("empty with thinking off by a zero budget", 20,
                        Map.of("thinking", Map.of("budget_tokens", 0)), empty, null),
                Arguments.of("empty with thinking level off", 20,
                        Map.of("thinking", Map.of("level", "off")), empty, null),
                Arguments.of("text without a finish reason", 20, null, response("Hello", null, null), null),
                Arguments.of("usage without a finish reason", 20, null,
                        response(null, null, new TokenUsage(22, 0, 38)), null),
                Arguments.of("Google's own stop", 20, null,
                        response("Hello", FinishReason.STOP, new TokenUsage(3, 1, 40)), FinishReason.STOP),
                Arguments.of("Google's own length", 20, null,
                        response(null, FinishReason.LENGTH, new TokenUsage(22, 0, 38)), FinishReason.LENGTH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("finishReasonCases")
    @DisplayName("keeps each finish reason, except an empty stream under an output limit with thinking not turned off, which gets length")
    void addsLengthOnlyToAnEmptyStreamUnderALimit(String name, Integer maxCompletionTokens,
            Map<String, Object> customParameters, ChatResponse response, FinishReason expected) {
        var result = LlmProviderGemini.withCutOffWhileThinking(request(maxCompletionTokens, customParameters),
                response);

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

    private static ChatCompletionRequest request(Integer maxCompletionTokens, Map<String, Object> customParameters) {
        return ChatCompletionRequest.builder()
                .model(MODEL)
                .addUserMessage("hello")
                .maxCompletionTokens(maxCompletionTokens)
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
                .modelName(MODEL)
                .maxOutputTokens(request.maxCompletionTokens())
                .returnThinking(false)
                .build()
                .chat(List.of(UserMessage.from("hello")),
                        LlmProviderGemini.streamHandler(request, delegate,
                                new StreamingResponseLogger("model=" + MODEL, MODEL)));

        assertThat(terminal.await(10, TimeUnit.SECONDS)).isTrue();
        return new StreamOutcome(messages, errors);
    }
}
