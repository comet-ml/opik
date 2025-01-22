package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.domain.llm.LlmProviderService;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class LlmProviderGemini implements LlmProviderService {
    private final @NonNull GeminiClientGenerator llmProviderClientGenerator;
    private final @NonNull String apiKey;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var mapper = LlmProviderGeminiMapper.INSTANCE;
        var response = llmProviderClientGenerator.generate(apiKey, request)
                .generate(request.messages().stream().map(mapper::toChatMessage).toList());

        return mapper.toChatCompletionResponse(request, response);
    }

    @Override
    public void generateStream(@NonNull ChatCompletionRequest request, @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage, @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        llmProviderClientGenerator.newGeminiStreamingClient(apiKey, request)
                .generate(request.messages().stream().map(LlmProviderGeminiMapper.INSTANCE::toChatMessage).toList(),
                        new ChunkedResponseHandler(handleMessage, handleClose, handleError, request.model()));
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {
    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable runtimeException) {
        return Optional.empty();
    }

}
