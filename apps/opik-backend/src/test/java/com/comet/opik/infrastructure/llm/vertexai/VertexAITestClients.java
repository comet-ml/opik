package com.comet.opik.infrastructure.llm.vertexai;

import com.google.cloud.vertexai.VertexAI;
import dev.langchain4j.model.chat.ChatModel;
import lombok.experimental.UtilityClass;

/** Reads back the {@link VertexAI} (and its host) that a generated model is bound to. */
@UtilityClass
class VertexAITestClients {

    static String apiEndpointOf(ChatModel model) {
        return vertexAiOf(model).getApiEndpoint();
    }

    static VertexAI vertexAiOf(ChatModel model) {
        if (model instanceof CloseableVertexAiChatModel wrapper) {
            return wrapper.vertexAI();
        }
        throw new AssertionError("Expected a CloseableVertexAiChatModel but got " + model.getClass());
    }
}
