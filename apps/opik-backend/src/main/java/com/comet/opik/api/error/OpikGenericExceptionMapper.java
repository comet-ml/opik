package com.comet.opik.api.error;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.ErrorMetrics;
import io.dropwizard.jersey.errors.LoggingExceptionMapper;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Catch-all for uncaught throwables (the 5xx path). Counts the error under {@code opik.errors.count}
 * tagged with the root-cause class name, then delegates to {@link LoggingExceptionMapper} to keep the
 * existing logging and {@code 500} rendering behaviour unchanged.
 */
@jakarta.ws.rs.ext.Provider
@Priority(1)
public class OpikGenericExceptionMapper extends LoggingExceptionMapper<Throwable> {

    private final ErrorMetrics errorMetrics;
    private final Provider<RequestContext> requestContext;
    private final Provider<UriInfo> uriInfo;

    @Inject
    public OpikGenericExceptionMapper(ErrorMetrics errorMetrics, Provider<RequestContext> requestContext,
            Provider<UriInfo> uriInfo) {
        this.errorMetrics = errorMetrics;
        this.requestContext = requestContext;
        this.uriInfo = uriInfo;
    }

    @Override
    public Response toResponse(Throwable exception) {
        errorMetrics.record(exception, uriInfo.get(), requestContext);
        return super.toResponse(exception);
    }
}
