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
     * base URL, or its own defaults, and exposes it only on the internal {@code ApiClient} — hence the reflection. It is
     * worth the reach: asserting on the location alone would not catch a configured endpoint being applied to a
     * single-region location, which is a silent misroute rather than a failure.
     */
    static String apiEndpointOf(ChatModel model) {
        try {
            var apiClientField = Client.class.getDeclaredField("apiClient");
            apiClientField.setAccessible(true);
            var apiClient = (ApiClient) apiClientField.get(clientOf(model));

            return ((HttpOptions) ApiClient.class.getMethod("httpOptions").invoke(apiClient))
                    .baseUrl()
                    .orElseThrow(() -> new AssertionError("Client settled on no base URL"));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not read the client's endpoint; the SDK's internals may have changed", e);
        }
    }
}
