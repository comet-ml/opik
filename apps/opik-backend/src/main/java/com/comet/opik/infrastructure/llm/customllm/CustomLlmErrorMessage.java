package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.dropwizard.jersey.errors.ErrorMessage;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomLlmErrorMessage(String error) implements LlmProviderError<String> {
    public ErrorMessage toErrorMessage() {
        return new ErrorMessage(400, error, "Custom Provider error");
    }

    @Override
    public String error() {
        return error;
    }
}