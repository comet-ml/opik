package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import io.dropwizard.jersey.errors.ErrorMessage;

import static com.comet.opik.infrastructure.llm.openai.OpenAiErrorMessage.OpenAiError;

public record OpenAiErrorMessage(OpenAiError error) implements LlmProviderError<OpenAiError> {

    public record OpenAiError(String message, String code, String type) {
    }

    public ErrorMessage toErrorMessage() {
        String message = error.message();

        if (message == null) {
            return null;
        }

        Integer code = getCode(error);

        if (code != null) {
            return new ErrorMessage(code, message);
        }

        return new ErrorMessage(500, error.message(), error.code);
    }

    private Integer getCode(OpenAiError error) {
        return switch (error.code) {
            case "invalid_api_key" -> 401;
            case "internal_error" -> 500;
            default -> null;
        };
    }
}
