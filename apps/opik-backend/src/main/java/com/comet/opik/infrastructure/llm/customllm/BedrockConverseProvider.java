package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.CustomParametersOverrides;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import com.comet.opik.infrastructure.llm.OpenAiCompatFinishReasons;
import com.comet.opik.utils.ClientDisconnectedException;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.shared.Usage;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
class BedrockConverseProvider implements LlmProviderService {

    private final @NonNull BedrockConverseClient client;
    private final String providerName;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var modelId = BedrockApi.modelId(request.model(), providerName);
        var response = client.converse(modelId, BedrockConverseMapper.toConverseRequest(request));
        return BedrockConverseMapper.toResponse(response, modelId);
    }

    @Override
    public void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        var modelId = BedrockApi.modelId(request.model(), providerName);
        client.converseStream(modelId, BedrockConverseMapper.toConverseRequest(request),
                new StreamListener(modelId, handleMessage, handleClose, handleError));
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {
        CustomParametersOverrides.rejectOpikManagedKeys(request.customParameters());
    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        if (throwable instanceof HttpException exception && exception.statusCode() >= 400
                && exception.statusCode() < 600) {
            return Optional.of(new ErrorMessage(exception.statusCode(),
                    BedrockConverseMapper.errorMessage(exception.getMessage())));
        }
        return LlmProviderLangChainMapper.INSTANCE.getCustomLlmErrorObject(throwable, log);
    }

    /**
     * Turns Converse's stream events into the chunks the playground reads: one per text delta, then a last one with
     * the finish reason and token usage, which Converse sends in two events after the text.
     */
    @RequiredArgsConstructor
    private static class StreamListener implements ServerSentEventListener {

        private final String modelId;
        private final Consumer<ChatCompletionResponse> handleMessage;
        private final Runnable handleClose;
        private final Consumer<Throwable> handleError;
        private final AtomicBoolean finished = new AtomicBoolean();
        private String stopReason;
        private Usage usage;

        @Override
        public void onEvent(ServerSentEvent event) {
            var payload = JsonUtils.getJsonNodeFromString(event.data());
            switch (event.event()) {
                case "contentBlockDelta" -> {
                    var text = payload.path("delta").path("text");
                    if (text.isTextual() && !text.asText().isEmpty()) {
                        send(BedrockConverseMapper.deltaChunk(modelId, text.asText(), null, null));
                    }
                }
                case "messageStop" -> stopReason = payload.path("stopReason").asText(null);
                case "metadata" -> usage = BedrockConverseMapper.usage(payload.path("usage"));
                default -> {
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (finished.compareAndSet(false, true)) {
                handleError.accept(throwable);
            }
        }

        @Override
        public void onClose() {
            if (finished.compareAndSet(false, true)) {
                send(BedrockConverseMapper.deltaChunk(modelId, "",
                        OpenAiCompatFinishReasons.toWireValue(BedrockConverseMapper.finishReason(stopReason)),
                        usage));
                handleClose.run();
            }
        }

        // Thrown on so the parser stops and the response is closed: nobody reads the rest of the stream.
        private void send(ChatCompletionResponse chunk) {
            try {
                handleMessage.accept(chunk);
            } catch (ClientDisconnectedException exception) {
                finished.set(true);
                throw exception;
            }
        }
    }
}
