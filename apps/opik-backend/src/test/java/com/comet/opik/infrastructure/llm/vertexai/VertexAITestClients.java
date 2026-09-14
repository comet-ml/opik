package com.comet.opik.infrastructure.llm.vertexai;

import com.google.genai.ApiClient;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import dev.langchain4j.model.chat.ChatModel;
import lombok.experimental.UtilityClass;

/** Reads back the {@link Client} that a generated model is bound to, and the endpoint it settled on. */
@UtilityClass
class VertexAITestClients {

    static Client clientOf(ChatModel model) {
        if (model instanceof CloseableVertexAiChatModel wrapper) {
            return wrapper.client();
        }
        throw new AssertionError("Expected a CloseableVertexAiChatModel but got " + model.getClass());
    }

    /**
     * The endpoint the client will actually call. The SDK resolves it at construction from the location, a configured
     * base URL, or its own defaults. Worth asserting on rather than the location alone, which would not catch a
     * configured endpoint being applied to a single-region location — a silent misroute rather than a failure.
     */
    static String apiEndpointOf(ChatModel model) {
        return httpOptionsOf(model).baseUrl()
                .orElseThrow(() -> new AssertionError("Client settled on no base URL"));
    }

    /** The request timeout the client settled on, in milliseconds. */
    static int timeoutOf(ChatModel model) {
        return httpOptionsOf(model).timeout()
                .orElseThrow(() -> new AssertionError("Client settled on no timeout"));
    }

    // The SDK settles these at construction and exposes them only on the internal ApiClient, hence the reflection.
    private static HttpOptions httpOptionsOf(ChatModel model) {
        try {
            var apiClientField = Client.class.getDeclaredField("apiClient");
            apiClientField.setAccessible(true);
            var apiClient = (ApiClient) apiClientField.get(clientOf(model));

            return (HttpOptions) ApiClient.class.getMethod("httpOptions").invoke(apiClient);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not read the client's HTTP options; the SDK's internals may have changed",
                    e);
        }
    }
}
