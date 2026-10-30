package com.comet.opik.infrastructure.llm.openai;

import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.blocking.ResponseService;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Fails a Responses API stream that ends without a final event, such as the empty body of a 204. LangChain4j reports
 * the outcome of a stream only from its final event, so without one the caller was never answered: neither completed
 * nor failed.
 */
@UtilityClass
class OpenAiResponsesStreamEnd {

    /** The message of the failure, which names nothing the provider sent. */
    static final String NO_FINAL_EVENT = "The LLM provider's stream ended without a final event";

    /** {@code client}, whose streams fail when they end without a final event. */
    OpenAIClient failingUnfinishedStreams(@NonNull OpenAIClient client) {
        var responses = client.responses();
        var checkedResponses = OpenAiResponsesExtraBody.newProxy(ResponseService.class, (self, method, args) -> {
            var result = OpenAiResponsesExtraBody.invoke(responses, method, args);
            return "createStreaming".equals(method.getName()) && result instanceof StreamResponse<?> stream
                    ? failingUnfinished(stream)
                    : result;
        });
        return OpenAiResponsesExtraBody.newProxy(OpenAIClient.class, (self, method, args) -> "responses"
                .equals(method.getName()) ? checkedResponses : OpenAiResponsesExtraBody.invoke(client, method, args));
    }

    private static StreamResponse<Object> failingUnfinished(StreamResponse<?> response) {
        return new StreamResponse<>() {
            @Override
            public Stream<Object> stream() {
                var finished = new AtomicBoolean();
                // the tail is evaluated only once every event was read
                return Stream.concat(
                        response.stream().peek(event -> finished.compareAndSet(false, isFinal(event))),
                        Stream.of(finished).flatMap(ended -> {
                            if (!ended.get()) {
                                throw new IllegalStateException(NO_FINAL_EVENT);
                            }
                            return Stream.empty();
                        }));
            }

            @Override
            public void close() {
                response.close();
            }
        };
    }

    private static boolean isFinal(Object event) {
        return event instanceof ResponseStreamEvent responseEvent && (responseEvent.isCompleted()
                || responseEvent.isFailed() || responseEvent.isIncomplete() || responseEvent.isError());
    }
}
