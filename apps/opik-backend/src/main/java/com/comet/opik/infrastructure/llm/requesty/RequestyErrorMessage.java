package com.comet.opik.infrastructure.llm.requesty;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.infrastructure.llm.requesty.RequestyErrorMessage.RequestyError;

/**
 * Requesty error envelope: {@code {"error":{"origin":"router","message":"..."}}}. Errors raised by the
 * router itself (unknown model, bad key) carry no {@code code}; errors forwarded from the upstream
 * vendor may carry a numeric one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RequestyErrorMessage(
        RequestyError error) implements LlmProviderError<RequestyError> {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RequestyError(String message, Integer code) {
    }

    public ErrorMessage toErrorMessage() {
        return toErrorMessage(null);
    }

    /**
     * @param responseStatus the HTTP status of the router response, used when the payload has no
     *                       {@code code} of its own; may be {@code null} when it is not known
     */
    public ErrorMessage toErrorMessage(Integer responseStatus) {
        // Deserialized straight from the provider response, so Bean Validation never runs:
        // guard both fields explicitly instead of relying on annotations.
        if (StringUtils.isBlank(error.message())) {
            return null;
        }

        Integer code = error.code() != null ? error.code() : responseStatus;
        return new ErrorMessage(toHttpStatus(code), error.message());
    }

    /**
     * Only 4xx and 5xx codes are accepted downstream: {@code ClientErrorException} and
     * {@code ServerErrorException} both validate the status family, so anything else (a missing
     * code, 0, a 2xx) is degraded to 500 instead of surfacing as an {@code IllegalArgumentException}.
     */
    private static int toHttpStatus(Integer code) {
        if (code == null) {
            return Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();
        }
        var family = Response.Status.Family.familyOf(code);
        boolean isErrorStatus = family == Response.Status.Family.CLIENT_ERROR
                || family == Response.Status.Family.SERVER_ERROR;
        return isErrorStatus ? code : Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();
    }
}
