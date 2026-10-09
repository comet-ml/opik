package com.comet.opik.api;

import com.comet.opik.infrastructure.llm.LoggingChunkedResponseHandler;
import com.comet.opik.infrastructure.llm.StreamingResponseLogger;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Streaming chunk handler")
class ChunkedResponseHandlerTest {

    private static final String MODEL = "gemini-3-flash-preview";

    private final List<ChatCompletionResponse> messages = new ArrayList<>();
    private final List<Throwable> errors = new ArrayList<>();
    private final AtomicInteger closes = new AtomicInteger();

    private ChunkedResponseHandler handler() {
        return new ChunkedResponseHandler(messages::add, closes::incrementAndGet, errors::add, MODEL);
    }

    private static ChatResponse response(FinishReason finishReason, TokenUsage tokenUsage) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(""))
                .finishReason(finishReason)
                .tokenUsage(tokenUsage)
                .build();
    }

    static Stream<Arguments> finishReasons() {
        return Stream.of(
                Arguments.of(FinishReason.LENGTH, "length"),
                Arguments.of(FinishReason.STOP, "stop"),
                Arguments.of(FinishReason.TOOL_EXECUTION, "tool_calls"),
                Arguments.of(FinishReason.CONTENT_FILTER, "content_filter"),
                Arguments.of(FinishReason.OTHER, "other"),
                Arguments.of(null, null));
    }

    @ParameterizedTest
    @MethodSource("finishReasons")
    @DisplayName("puts the provider's finish reason on the final chunk")
    void finalChunkCarriesTheFinishReason(FinishReason finishReason, String expected) {
        handler().onCompleteResponse(response(finishReason, new TokenUsage(38, 0, 55)));

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().choices().getFirst().finishReason()).isEqualTo(expected);
        assertThat(messages.getFirst().usage().promptTokens()).isEqualTo(38);
        assertThat(closes).hasValue(1);
    }

    @Test
    @DisplayName("finishes a stream that reported no token usage instead of failing it")
    void streamWithoutTokenUsageCompletes() {
        var logger = new StreamingResponseLogger("model=" + MODEL, MODEL);

        new LoggingChunkedResponseHandler(handler(), logger)
                .onCompleteResponse(response(FinishReason.LENGTH, null));

        assertThat(errors).isEmpty();
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().usage()).isNull();
        assertThat(messages.getFirst().choices().getFirst().finishReason()).isEqualTo("length");
        assertThat(closes).hasValue(1);
    }
}
