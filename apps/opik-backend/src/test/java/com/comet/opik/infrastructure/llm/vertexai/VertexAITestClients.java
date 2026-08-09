package com.comet.opik.infrastructure.llm.vertexai;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiChatModel;
import lombok.experimental.UtilityClass;

import java.lang.reflect.Field;

/**
 * Reads back the {@link VertexAI} a generated model is bound to. The model exposes neither it nor the host it settled
 * on, and both are needed: the host for the cases that must not issue a request — a single-region location resolves to
 * a real Google host, so calling it would mean network egress and DNS timeouts in CI — and the client itself to tell a
 * reused instance from a freshly built one.
 */
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
