package com.comet.opik.domain.llmproviders;

import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.WebApplicationException;
import lombok.NonNull;

import java.util.function.Consumer;

public interface LlmProviderService {
    ChatCompletionResponse generate(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId);

    void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError);

    WebApplicationException mapRuntimeException(RuntimeException runtimeException);

    ErrorMessage mapThrowableToError(Throwable throwable);
}
