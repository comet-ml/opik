package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class LlmProviderVertexAI implements LlmProviderService {

    private final @NonNull VertexAIClientGenerator llmProviderClientGenerator;
    private final @NonNull LlmProviderClientApiConfig config;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        ChatResponse response = llmProviderClientGenerator.generate(config, request).chat(getChatMessages(request));
        return LlmProviderLangChainMapper.INSTANCE.toChatCompletionResponse(request, response);
    }

    @Override
    public void generateStream(@NonNull ChatCompletionRequest request, @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage, @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {

        Schedulers.boundedElastic()
                .schedule(() -> {
                    try {
                        var streamingChatLanguageModel = llmProviderClientGenerator.newVertexAIStreamingClient(config,
                                request);

                        List<ChatMessage> chatMessages = getChatMessages(request);
                        streamingChatLanguageModel
                                .chat(chatMessages,
                                        new ChunkedResponseHandler(handleMessage, handleClose, handleError,
                                                request.model()));
                    } catch (Exception e) {
                        handleError.accept(e);
                        handleClose.run();
                    }
                });
    }

    private List<ChatMessage> getChatMessages(ChatCompletionRequest request) {
        List<ChatMessage> chatMessages = LlmProviderLangChainMapper.INSTANCE.mapMessages(request);

        // This is a workaround for the Vertex AI API, which requires at least one user or AI message in the request.
        if (chatMessages.stream().noneMatch(chatMessage -> chatMessage.type() == ChatMessageType.AI
                || chatMessage.type() == ChatMessageType.USER)) {
            var newMessages = new ArrayList<ChatMessage>();
            newMessages.add(AiMessage.from("User message:")); // Add an empty user message to the list as has to have at least one user or ai message
            newMessages.addAll(chatMessages);
            chatMessages = newMessages;
        }

        return chatMessages;
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {

    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return LlmProviderLangChainMapper.INSTANCE.getGeminiErrorObject(throwable, log);
    }
}
