package com.comet.opik.infrastructure.llm.vertexai;

import com.google.genai.Client;
import dev.langchain4j.model.chat.ChatModel;
import lombok.experimental.UtilityClass;

/** Reads back the {@link Client} that a generated model is bound to. */
@UtilityClass
class VertexAITestClients {

    static Client clientOf(ChatModel model) {
        if (model instanceof CloseableVertexAiChatModel wrapper) {
            return wrapper.client();
        }
        throw new AssertionError("Expected a CloseableVertexAiChatModel but got " + model.getClass());
    }
}
