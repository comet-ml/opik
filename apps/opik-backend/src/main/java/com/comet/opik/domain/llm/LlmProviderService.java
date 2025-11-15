package com.comet.opik.domain.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public interface LlmProviderService {
    ChatCompletionResponse generate(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            Map<String, Object> requestExtraBody);

    void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            Map<String, Object> requestExtraBody,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError);

    void validateRequest(@NonNull ChatCompletionRequest request);

    Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable);
}
