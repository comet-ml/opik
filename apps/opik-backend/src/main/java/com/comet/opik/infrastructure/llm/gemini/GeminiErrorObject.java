package com.comet.opik.infrastructure.llm.gemini;

import io.dropwizard.jersey.errors.ErrorMessage;

import java.util.Optional;

public record GeminiErrorObject(GeminiError error) {
    public Optional<ErrorMessage> toErrorMessage() {
        if (error != null) {
            return Optional.of(new ErrorMessage(error.code(), error.message(), error().status()));
        }

        return Optional.empty();
    }
}

record GeminiError(int code, String message, String status) {
}
