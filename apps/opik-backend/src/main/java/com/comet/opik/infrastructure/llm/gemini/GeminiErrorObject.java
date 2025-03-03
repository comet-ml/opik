package com.comet.opik.infrastructure.llm.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.dropwizard.jersey.errors.ErrorMessage;

import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiErrorObject(GeminiError error) {
    public Optional<ErrorMessage> toErrorMessage() {
        if (error != null) {
            return Optional.of(new ErrorMessage(error.code(), error.message(), error().status()));
        }

        return Optional.empty();
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
record GeminiError(int code, String message, String status) {
}
