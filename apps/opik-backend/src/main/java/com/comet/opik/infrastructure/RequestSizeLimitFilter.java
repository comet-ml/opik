package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/**
 * Rejects an oversized request with 413 by its {@code Content-Length}, before the body is read and ahead of
 * authentication (low {@code @Priority}). Global (no path scoping) by design, so it caps every request
 * carrying a Content-Length; a chunked request has none, passes here, and is still bounded by
 * {@code maxDocumentLength} during parsing.
 */
@Slf4j
@Priority(Priorities.HEADER_DECORATOR)
public class RequestSizeLimitFilter implements ContainerRequestFilter {

    private final JacksonConfig jacksonConfig;
    private final IngestionSizeGuardMetrics sizeGuardMetrics;
    private final Provider<RequestContext> requestContext;

    @Inject
    public RequestSizeLimitFilter(@Config JacksonConfig jacksonConfig,
            IngestionSizeGuardMetrics sizeGuardMetrics, Provider<RequestContext> requestContext) {
        this.jacksonConfig = jacksonConfig;
        this.sizeGuardMetrics = sizeGuardMetrics;
        this.requestContext = requestContext;
    }

    @Override
    public void filter(ContainerRequestContext containerRequestContext) {
        String contentLengthHeader = containerRequestContext.getHeaderString(HttpHeaders.CONTENT_LENGTH);

        // No Content-Length (chunked): can't check here; the body is still bounded by maxDocumentLength at parse time.
        if (contentLengthHeader == null) {
            return;
        }

        // Parse as long, not jakarta's int getLength() (returns -1 above ~2GB, letting the largest bodies
        // slip). A present-but-invalid Content-Length is malformed framing -> fail closed with 400.
        long contentLength;
        try {
            contentLength = Long.parseLong(contentLengthHeader.trim());
        } catch (NumberFormatException e) {
            abort(containerRequestContext, Response.Status.BAD_REQUEST, "Invalid Content-Length header");
            return;
        }

        if (contentLength < 0) {
            abort(containerRequestContext, Response.Status.BAD_REQUEST, "Invalid Content-Length header");
            return;
        }

        long maxRequestSizeBytes = jacksonConfig.getMaxRequestSizeBytes();
        if (contentLength > maxRequestSizeBytes) {
            log.warn("Rejecting request with Content-Length '{}' bytes exceeding limit '{}' bytes",
                    contentLength, maxRequestSizeBytes);
            sizeGuardMetrics.recordRequestSizeRejection(containerRequestContext.getUriInfo(), requestContext);
            abort(containerRequestContext, Response.Status.REQUEST_ENTITY_TOO_LARGE,
                    "Request body exceeds the maximum allowed size of %d bytes".formatted(maxRequestSizeBytes));
        }
    }

    private void abort(ContainerRequestContext containerRequestContext, Response.Status status, String message) {
        containerRequestContext.abortWith(Response.status(status)
                .entity(new ErrorMessage(status.getStatusCode(), message))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .build());
    }
}
