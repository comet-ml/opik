package com.comet.opik.infrastructure.llm.customllm;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class BedrockConverseChatModel implements ChatModel {

    private final @NonNull BedrockConverseClient client;
    private final @NonNull String modelId;
    private final @NonNull ChatRequestParameters defaultRequestParameters;
    private final @Nullable JsonNode customParameters;

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return defaultRequestParameters;
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        var response = client.converse(modelId,
                BedrockConverseMapper.toConverseRequest(chatRequest.messages(), chatRequest.parameters(),
                        customParameters));
        return BedrockConverseMapper.toChatResponse(response, modelId);
    }
}
