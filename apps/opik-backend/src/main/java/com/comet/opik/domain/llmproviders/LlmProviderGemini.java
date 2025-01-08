package com.comet.opik.domain.llmproviders;

import dev.ai4j.openai4j.chat.AssistantMessage;
import dev.ai4j.openai4j.chat.ChatCompletionChoice;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.ai4j.openai4j.chat.Message;
import dev.ai4j.openai4j.shared.Usage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class LlmProviderGemini implements LlmProviderService {
    private final @NonNull LlmProviderClientGenerator llmProviderClientGenerator;
    private final @NonNull String apiKey;

    private static final String ERR_UNEXPECTED_ROLE = "unexpected role '%s'";

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var response = llmProviderClientGenerator.newGeminiClient(apiKey, request)
                .generate(request.messages().stream().map(this::toChatMessage).toList());

        return ChatCompletionResponse.builder()
                .model(request.model())
                .choices(List.of(ChatCompletionChoice.builder()
                        .message(AssistantMessage.builder().content(response.content().text()).build())
                        .build()))
                .usage(Usage.builder()
                        .promptTokens(response.tokenUsage().inputTokenCount())
                        .completionTokens(response.tokenUsage().outputTokenCount())
                        .totalTokens(response.tokenUsage().totalTokenCount())
                        .build())
                .build();
    }

    @Override
    public void generateStream(@NonNull ChatCompletionRequest request, @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage, @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        llmProviderClientGenerator.newGeminiStreamingClient(apiKey, request)
                .generate(request.messages().stream().map(this::toChatMessage).toList(),
                        new ChunkedResponseHandler(handleMessage, handleClose, handleError, request.model()));
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {
    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable runtimeException) {
        return Optional.empty();
    }

    private ChatMessage toChatMessage(Message message) {
        switch (message.role()) {
            case ASSISTANT -> {
                return AiMessage.from(((AiMessage) message).text());
            }
            case USER -> {
                return UserMessage
                        .from(toStringMessageContent(((dev.ai4j.openai4j.chat.UserMessage) message).content()));
            }
            case SYSTEM -> {
                return SystemMessage.from(((dev.ai4j.openai4j.chat.SystemMessage) message).content());
            }
        }

        throw new BadRequestException(ERR_UNEXPECTED_ROLE.formatted(message.role()));
    }

    private String toStringMessageContent(Object rawContent) {
        if (rawContent instanceof String content) {
            return content;
        }

        throw new BadRequestException("only text content is supported");
    }
}
