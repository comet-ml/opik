package com.comet.opik.infrastructure.llm.openrouter;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import static com.comet.opik.infrastructure.llm.openrouter.OpenRouterErrorMessage.OpenRouterError;

public record OpenRouterErrorMessage(
        OpenRouterError error) implements LlmProviderError<OpenRouterError> {

    public record OpenRouterError(@NotBlank String message, @NotNull Integer code) {
    }

    public ErrorMessage toErrorMessage() {
        return new ErrorMessage(error.code(), error.message());
    }

}
