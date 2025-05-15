package com.comet.opik.api;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Delta;
import dev.langchain4j.model.openai.internal.chat.Role;
import dev.langchain4j.model.openai.internal.shared.Usage;
import dev.langchain4j.model.output.Response;
import lombok.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public record ChunkedResponseHandler(
        @NonNull Consumer<ChatCompletionResponse> handleMessage,
        @NonNull Runnable handleClose,
        @NonNull Consumer<Throwable> handleError,
        @NonNull String model) implements StreamingResponseHandler<AiMessage>, StreamingChatResponseHandler {

    @Override
    public void onNext(@NonNull String content) {
        handleMessage.accept(ChatCompletionResponse.builder()
                .model(model)
                .choices(List.of(ChatCompletionChoice.builder()
                        .delta(Delta.builder()
                                .content(content)
                                .role(Role.ASSISTANT.name().toLowerCase())
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
                                .role(Role.ASSISTANT.name().toLowerCase())
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
    public void onPartialResponse(String s) {
        this.onNext(s);
    }

    @Override
    public void onCompleteResponse(ChatResponse chatResponse) {
        this.onComplete(Response.from(
                chatResponse.aiMessage(),
                chatResponse.tokenUsage(),
                chatResponse.finishReason(),
                getMetadata(chatResponse)));
    }

    private Map<String, Object> getMetadata(ChatResponse chatResponse) {
        if (chatResponse.metadata() == null) {
            return Map.of();
        }

        Map<String, Object> metadata = new HashMap<>();

        if (chatResponse.metadata().modelName() != null) {
            metadata.put("modelName", chatResponse.metadata().modelName());
            metadata.put("model", chatResponse.metadata().modelName());
        }

        if (chatResponse.metadata().id() != null) {
            metadata.put("id", chatResponse.metadata().id());
        }

        return metadata;
    }

    @Override
    public void onError(@NonNull Throwable throwable) {
        handleError.accept(throwable);
    }
}
