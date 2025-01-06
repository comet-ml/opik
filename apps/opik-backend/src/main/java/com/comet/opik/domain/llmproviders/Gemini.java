package com.comet.opik.domain.llmproviders;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import lombok.NonNull;
import org.apache.commons.lang3.NotImplementedException;

import java.util.Optional;
import java.util.function.Consumer;

public class Gemini implements LlmProviderService {
    private final LlmProviderClientConfig llmProviderClientConfig;

    public Gemini(LlmProviderClientConfig llmProviderClientConfig, String apiKey) {
        this.llmProviderClientConfig = llmProviderClientConfig;
    }

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        throw new NotImplementedException("Gemini not implemented yet");
    }

    @Override
    public void generateStream(@NonNull ChatCompletionRequest request, @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage, @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        throw new NotImplementedException("Gemini not implemented yet");
    }

    @Override
    public void validateRequest(ChatCompletionRequest request) {
        throw new NotImplementedException("Gemini not implemented yet");
    }

    @Override
    public Optional<LlmProviderError> getLlmProviderError(Throwable runtimeException) {
        throw new NotImplementedException("Gemini not implemented yet");
    }
}
