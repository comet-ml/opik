package com.comet.opik.infrastructure.llm.orcarouter;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import static com.comet.opik.infrastructure.llm.orcarouter.OrcaRouterErrorMessage.OrcaRouterError;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrcaRouterErrorMessage(
        OrcaRouterError error) implements LlmProviderError<OrcaRouterError> {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrcaRouterError(@NotBlank String message, @NotNull Integer code) {
    }

    public ErrorMessage toErrorMessage() {
        return new ErrorMessage(error.code(), error.message());
    }
}
