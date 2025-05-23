package com.comet.opik.infrastructure.llm.openrouter;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import io.dropwizard.jersey.errors.ErrorMessage;

import static com.comet.opik.infrastructure.llm.openrouter.OpenRouterErrorMessage.OpenRouterError;

public record OpenRouterErrorMessage(
        OpenRouterError error) implements LlmProviderError<OpenRouterError> {

    public record OpenRouterError(String message, Integer code) {
    }

    public ErrorMessage toErrorMessage() {
        return new ErrorMessage(error.code(), error.message());
    }

}
