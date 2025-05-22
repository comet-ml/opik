package com.comet.opik.infrastructure.llm.openrouter;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import io.dropwizard.jersey.errors.ErrorMessage;

public record OpenRouterErrorMessage(
        OpenRouterError error) implements LlmProviderError<OpenRouterErrorMessage.OpenRouterError> {

    public record OpenRouterError(String message, Integer code) {
    }

    public ErrorMessage toErrorMessage() {
        return new ErrorMessage(error.code(), error.message());
    }

}
