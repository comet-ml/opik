package com.comet.opik.infrastructure.llm;

import io.dropwizard.jersey.errors.ErrorMessage;

public interface LlmProviderError<T> {
    T error();

    ErrorMessage toErrorMessage();
}
