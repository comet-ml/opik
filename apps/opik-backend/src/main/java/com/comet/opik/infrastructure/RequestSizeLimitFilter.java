package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/**
 * Rejects an oversized request with 413 based on its {@code Content-Length}, before the body is
 * read. This fast-fails a large ingestion batch at the request boundary so the JSON parser never
 * starts building a node tree from it.
 *
 * The header reflects the on-the-wire size (compressed, when the client gzips). A request with no
 * {@code Content-Length} (e.g. chunked transfer) is legitimate and passes here; it is still bounded
 * by the {@code maxDocumentLength} StreamReadConstraint enforced during parsing.
 *
 * Guicey auto-installs this as a JAX-RS provider and injects {@link JacksonConfig} via the Guicey
 * {@code @Config} sub-config binding (no explicit module binding needed).
 */
@Slf4j
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

        // No Content-Length (e.g. chunked transfer-encoding): can't check here, let it through.
        // The decompressed body is still bounded by maxDocumentLength during parsing.
        if (contentLengthHeader == null) {
            return;
        }

        // Parse as a long: jakarta's getLength() is an int and returns -1 for bodies above
        // Integer.MAX_VALUE (~2GB), which would let the very largest payloads slip through. Treat a
        // present-but-invalid Content-Length as malformed HTTP framing and fail closed with 400,
        // rather than silently ignoring it.
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
