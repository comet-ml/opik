package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.dropwizard.jersey.errors.ErrorMessage;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiErrorObject(GeminiError error) implements LlmProviderError<GeminiError> {
    public ErrorMessage toErrorMessage() {
        return new ErrorMessage(error.code(), error.message(), error().status());
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
record GeminiError(int code, String message, String status) {
}
