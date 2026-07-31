package com.comet.opik.infrastructure.llm.requesty;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.infrastructure.llm.requesty.RequestyErrorMessage.RequestyError;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RequestyErrorMessage(
        RequestyError error) implements LlmProviderError<RequestyError> {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RequestyError(String message, Integer code) {
    }

    public ErrorMessage toErrorMessage() {
        // Deserialized straight from the provider response, so Bean Validation never runs:
        // guard both fields explicitly instead of relying on annotations.
        if (StringUtils.isBlank(error.message())) {
            return null;
        }

        if (error.code() == null) {
            return new ErrorMessage(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), error.message());
        }

        return new ErrorMessage(error.code(), error.message());
    }
}
