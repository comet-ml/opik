package com.comet.opik.api.error;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.ErrorMetrics;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import lombok.RequiredArgsConstructor;

/**
 * Counts server-error {@link WebApplicationException}s (e.g. {@code InternalServerErrorException},
 * {@code ServiceUnavailableException}) under {@code opik.errors.count}, then preserves the existing
 * behaviour by returning the exception's own response. Only {@code 5xx} statuses are counted: 4xx
 * (validation/auth/not-found) are client errors, and the cause they carry is rarely actionable for
 * triage. Uncaught (non-{@link WebApplicationException}) 5xx errors are handled by
 * {@link OpikGenericExceptionMapper}.
 */
@jakarta.ws.rs.ext.Provider
@Priority(1)
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OpikWebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

    private final ErrorMetrics errorMetrics;
    private final Provider<RequestContext> requestContext;
    private final Provider<UriInfo> uriInfo;

    @Override
    public Response toResponse(WebApplicationException exception) {
        Response response = exception.getResponse();
        if (response != null && response.getStatus() >= Response.Status.INTERNAL_SERVER_ERROR.getStatusCode()) {
            errorMetrics.record(exception, uriInfo.get(), requestContext);
        }
        return response;
    }
}
