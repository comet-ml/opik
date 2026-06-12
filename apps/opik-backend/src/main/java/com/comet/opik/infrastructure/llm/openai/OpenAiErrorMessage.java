package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import com.comet.opik.infrastructure.llm.OpenAiCompatStatusCodes;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.validation.constraints.NotBlank;

import static com.comet.opik.infrastructure.llm.openai.OpenAiErrorMessage.OpenAiError;

public record OpenAiErrorMessage(OpenAiError error) implements LlmProviderError<OpenAiError> {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenAiError(@NotBlank String message, @NotBlank String code, @NotBlank String type) {
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

    /**
     * OpenAI's error payload carries both a high-level {@code type}
     * ({@code "invalid_request_error"}, …) and a specific {@code code}
     * ({@code "unsupported_parameter"}, {@code "model_not_found"}, …). The {@code code} is more
     * diagnostic, but our map can't enumerate every specific value OpenAI may emit; fall back to
     * {@code type} so the HTTP status family stays correct even on unrecognized specific codes.
     */
    private Integer getCode(OpenAiError error) {
        Integer status = OpenAiCompatStatusCodes.fromCode(error.code);
        if (status != null) {
            return status;
        }
        return OpenAiCompatStatusCodes.fromCode(error.type);
    }
}
