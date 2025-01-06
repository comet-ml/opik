package com.comet.opik.domain.llmproviders;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
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
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class Gemini implements LlmProviderService {
    private final LlmProviderClientConfig llmProviderClientConfig;
    private final String apiKey;

    private static final String ERR_UNEXPECTED_ROLE = "unexpected role '%s'";

    public Gemini(LlmProviderClientConfig llmProviderClientConfig, String apiKey) {
        this.llmProviderClientConfig = llmProviderClientConfig;
        this.apiKey = apiKey;
    }

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        GoogleAiGeminiChatModel client = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(request.model())
                .maxOutputTokens(request.maxCompletionTokens())
                .maxRetries(1)
                .stopSequences(request.stop())
                .temperature(request.temperature())
                .topP(request.topP())
                .timeout(llmProviderClientConfig.getCallTimeout().toJavaDuration())
                .build();
        var response = client.generate(request.messages().stream().map(this::toChatMessage).toList());

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
        var client = GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(request.model())
                .maxOutputTokens(request.maxCompletionTokens())
                .maxRetries(1)
                .stopSequences(request.stop())
                .temperature(request.temperature())
                .topP(request.topP())
                .timeout(llmProviderClientConfig.getCallTimeout().toJavaDuration())
                .build();
        client.generate(request.messages().stream().map(this::toChatMessage).toList(),
                new ChunkedResponseHandler(handleMessage, handleClose, handleError, request.model()));
    }

    @Override
    public void validateRequest(ChatCompletionRequest request) {
    }

    @Override
    public Optional<LlmProviderError> getLlmProviderError(Throwable runtimeException) {
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
