package com.comet.opik.api;

import dev.ai4j.openai4j.chat.ChatCompletionChoice;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.ai4j.openai4j.chat.Delta;
import dev.ai4j.openai4j.chat.Role;
import dev.ai4j.openai4j.shared.Usage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import lombok.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public record ChunkedResponseHandler(
        @NonNull Consumer<ChatCompletionResponse> handleMessage,
        @NonNull Runnable handleClose,
        @NonNull Consumer<Throwable> handleError,
        @NonNull String model) implements StreamingResponseHandler<AiMessage> {

    @Override
    public void onNext(@NonNull String content) {
        handleMessage.accept(ChatCompletionResponse.builder()
                .model(model)
                .choices(List.of(ChatCompletionChoice.builder()
                        .delta(Delta.builder()
                                .content(content)
                                .role(Role.ASSISTANT)
                                .build())
                        .build()))
                .build());
    }

    @Override
    public void onComplete(@NonNull Response<AiMessage> response) {
        handleMessage.accept(ChatCompletionResponse.builder()
                .model(model)
                .choices(List.of(ChatCompletionChoice.builder()
                        .delta(Delta.builder()
                                .content("")
                                .role(Role.ASSISTANT)
                                .build())
                        .build()))
                .usage(Usage.builder()
                        .promptTokens(response.tokenUsage().inputTokenCount())
                        .completionTokens(response.tokenUsage().outputTokenCount())
                        .totalTokens(response.tokenUsage().totalTokenCount())
                        .build())
                .id(Optional.ofNullable(response.metadata().get("id")).map(Object::toString).orElse(null))
                .build());
        handleClose.run();
    }

    @Override
    public void onError(@NonNull Throwable throwable) {
        handleError.accept(throwable);
    }
}
