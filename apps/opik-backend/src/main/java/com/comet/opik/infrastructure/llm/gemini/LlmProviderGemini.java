package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static jakarta.ws.rs.core.Response.Status.Family.familyOf;

@RequiredArgsConstructor
public class LlmProviderGemini implements LlmProviderService {
    private final @NonNull GeminiClientGenerator llmProviderClientGenerator;
    private final @NonNull String apiKey;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var mapper = LlmProviderGeminiMapper.INSTANCE;
        var response = llmProviderClientGenerator.generate(apiKey, request)
                .generate(request.messages().stream().map(mapper::toChatMessage).toList());

        return mapper.toChatCompletionResponse(request, response);
    }

    @Override
    public void generateStream(@NonNull ChatCompletionRequest request, @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage, @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        CompletableFuture.runAsync(() -> llmProviderClientGenerator.newGeminiStreamingClient(apiKey, request)
                .generate(request.messages().stream().map(LlmProviderGeminiMapper.INSTANCE::toChatMessage).toList(),
                        new ChunkedResponseHandler(handleMessage, handleClose, handleError, request.model())));
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {
    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        /// gemini throws RuntimeExceptions with message structure as follows:
        /// ```
        /// java.lang.RuntimeException: HTTP error (429): {
        ///   "error": {
        ///     "code": 429,
        ///     "message": "Resource has been exhausted (e.g. check quota).",
        ///     "status": "RESOURCE_EXHAUSTED"
        ///   }
        /// }
        ///  ```
        String message = throwable.getMessage();
        if (message.contains("{")) {
            String jsonPart = message.substring(message.indexOf("{")); // Extract JSON part
            try {
                // Parse JSON
                JsonNode errorNode = JsonUtils.MAPPER.readTree(jsonPart).get("error");
                if (errorNode != null) {
                    var code = errorNode.get("code").asInt();
                    if (familyOf(code) == Response.Status.Family.CLIENT_ERROR) {
                        // Customize the message based on the error
                        return Optional.of(new ErrorMessage(
                                code,
                                errorNode.get("message").asText(),
                                errorNode.get("status").asText()));
                    }
                }
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        return Optional.empty();
    }
}
