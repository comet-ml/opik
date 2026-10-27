package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.infrastructure.llm.LoggingChunkedResponseHandler;
import com.comet.opik.infrastructure.llm.StreamingResponseLogger;
import com.comet.opik.utils.ClientDisconnectedException;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** A partial response whose caller is gone cancels the provider call, and the handler doesn't fail. */
class StreamingHandlersDisconnectTest {

    private static final String MODEL = "model-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(8));

    private static Stream<Arguments> aDisconnectedCallerCancelsTheStream() {
        Function<Consumer<ChatCompletionResponse>, StreamingChatResponseHandler> chunked = handleMessage -> new ChunkedResponseHandler(
                handleMessage, () -> {
                }, error -> {
                }, MODEL);
        Function<Consumer<ChatCompletionResponse>, StreamingChatResponseHandler> logging = handleMessage -> new LoggingChunkedResponseHandler(
                new ChunkedResponseHandler(handleMessage, () -> {
                }, error -> {
                }, MODEL), new StreamingResponseLogger("model=%s".formatted(MODEL), MODEL));
        Function<Consumer<ChatCompletionResponse>, StreamingChatResponseHandler> responses = handleMessage -> new OpenAiResponsesStreamingHandler(
                ChatCompletionRequest.builder().model(MODEL).build(), handleMessage, () -> {
                }, error -> {
                });
        return Stream.of(
                arguments("ChunkedResponseHandler", chunked),
                arguments("LoggingChunkedResponseHandler", logging),
                arguments("OpenAiResponsesStreamingHandler", responses));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void aDisconnectedCallerCancelsTheStream(String handlerName,
            Function<Consumer<ChatCompletionResponse>, StreamingChatResponseHandler> newHandler) {
        var streamingHandle = mock(StreamingHandle.class);
        var handler = newHandler.apply(response -> {
            throw new ClientDisconnectedException(new IOException("closed"));
        });

        assertThatCode(() -> handler.onPartialResponse(
                new PartialResponse(RandomStringUtils.secure().nextAlphanumeric(8)),
                new PartialResponseContext(streamingHandle)))
                .doesNotThrowAnyException();

        verify(streamingHandle).cancel();
    }
}
