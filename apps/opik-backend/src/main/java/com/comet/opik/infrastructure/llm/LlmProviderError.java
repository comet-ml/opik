package com.comet.opik.infrastructure.llm;

import io.dropwizard.jersey.errors.ErrorMessage;

public interface LlmProviderError<T> {
    T error();

    ErrorMessage toErrorMessage();

    /** Whether the envelope names an error the provider documents, which decides whether its text is shown. */
    boolean isDocumented();
}
