package com.comet.opik.api.error;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.HttpErrorMetrics;
import io.dropwizard.jersey.errors.LoggingExceptionMapper;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Counts server-error {@link WebApplicationException}s (e.g. {@code InternalServerErrorException},
 * {@code ServiceUnavailableException}) under {@code opik.errors.count}, then delegates to
 * {@link LoggingExceptionMapper} to keep the existing logging and {@code ErrorMessage} rendering
 * behaviour unchanged. Only {@code 5xx} statuses are counted: 4xx (validation/auth/not-found) are
 * client errors, and the cause they carry is rarely actionable for triage. Uncaught
 * (non-{@link WebApplicationException}) 5xx errors are handled by {@link OpikGenericExceptionMapper}.
 */
@jakarta.ws.rs.ext.Provider
@Priority(1)
public class OpikWebApplicationExceptionMapper extends LoggingExceptionMapper<WebApplicationException> {

    private final HttpErrorMetrics errorMetrics;
    private final Provider<RequestContext> requestContext;
    private final Provider<Request> request;
    private final Provider<UriInfo> uriInfo;

    @Inject
    public OpikWebApplicationExceptionMapper(HttpErrorMetrics errorMetrics, Provider<RequestContext> requestContext,
            Provider<Request> request, Provider<UriInfo> uriInfo) {
        this.errorMetrics = errorMetrics;
        this.requestContext = requestContext;
        this.request = request;
        this.uriInfo = uriInfo;
    }

    @Override
    public Response toResponse(WebApplicationException exception) {
        Response response = exception.getResponse();
        if (response != null && response.getStatus() >= Response.Status.INTERNAL_SERVER_ERROR.getStatusCode()) {
            errorMetrics.record(exception, request, uriInfo.get(), requestContext);
        }
        return super.toResponse(exception);
    }
}
