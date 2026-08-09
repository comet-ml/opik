package com.comet.opik.infrastructure.llm.vertexai;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiChatModel;
import lombok.experimental.UtilityClass;

import java.lang.reflect.Field;

/** Reads back the {@link VertexAI} (and the host) a generated model is bound to — neither is exposed publicly. */
@UtilityClass
class VertexAITestClients {

    static String apiEndpointOf(ChatModel model) {
        return vertexAiOf(model).getApiEndpoint();
    }

    static VertexAI vertexAiOf(ChatModel model) {
        try {
            Field generativeModelField = VertexAiGeminiChatModel.class.getDeclaredField("generativeModel");
            generativeModelField.setAccessible(true);
            var generativeModel = generativeModelField.get(model);

            Field vertexAiField = GenerativeModel.class.getDeclaredField("vertexAi");
            vertexAiField.setAccessible(true);

            return (VertexAI) vertexAiField.get(generativeModel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not read the client off the generated model", e);
        }
    }
}
