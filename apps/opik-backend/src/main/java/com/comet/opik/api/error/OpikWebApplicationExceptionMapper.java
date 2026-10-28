package com.comet.opik.api.error;

import com.comet.opik.domain.llm.ProviderFailures;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /** The logger {@link LoggingExceptionMapper} logs to, which it keeps private. */
    private static final Logger EXCEPTION_LOGGER = LoggerFactory.getLogger(LoggingExceptionMapper.class);

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

    /**
     * An exception carrying a redacted LLM provider failure is logged redacted too: its message can be what the
     * provider answered, which the caller still gets. See {@link ProviderFailures}.
     */
    @Override
    protected void logException(long id, WebApplicationException exception) {
        if (ProviderFailures.carriesRedacted(exception)) {
            EXCEPTION_LOGGER.error(formatLogMessage(id, exception), ProviderFailures.redacted(exception));
            return;
        }
        super.logException(id, exception);
    }
}
