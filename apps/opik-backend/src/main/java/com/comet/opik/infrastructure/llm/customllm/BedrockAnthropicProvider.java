package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.domain.llm.LlmProviderService;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ObjectUtils;

import java.util.Optional;
import java.util.function.Consumer;

@RequiredArgsConstructor
class BedrockAnthropicProvider implements LlmProviderService {

    private final @NonNull LlmProviderService anthropic;
    private final String providerName;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        return anthropic.generate(toBedrockRequest(request), workspaceId);
    }

    @Override
    public void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        anthropic.generateStream(toBedrockRequest(request), workspaceId, handleMessage, handleClose, handleError);
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {
        anthropic.validateRequest(toBedrockRequest(request));
    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return anthropic.getLlmProviderError(throwable);
    }

    // The playground's token slider allows 0, which Anthropic rejects; without a positive limit the Anthropic mapper
    // applies its default.
    private ChatCompletionRequest toBedrockRequest(ChatCompletionRequest request) {
        return ChatCompletionRequest.builder()
                .from(request)
                .model(BedrockApi.modelId(request.model(), providerName))
                .maxCompletionTokens(ObjectUtils.firstNonNull(
                        positiveOrNull(request.maxCompletionTokens()), positiveOrNull(request.maxTokens())))
                .maxTokens(null)
                .build();
    }

    private static Integer positiveOrNull(Integer tokens) {
        return tokens != null && tokens > 0 ? tokens : null;
    }
}
