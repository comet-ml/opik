package com.comet.opik.infrastructure.llm.requesty;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import static com.comet.opik.infrastructure.llm.requesty.RequestyErrorMessage.RequestyError;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RequestyErrorMessage(
        RequestyError error) implements LlmProviderError<RequestyError> {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RequestyError(@NotBlank String message, @NotNull Integer code) {
    }

    public ErrorMessage toErrorMessage() {
        return new ErrorMessage(error.code(), error.message());
    }
}
