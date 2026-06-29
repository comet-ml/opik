package com.comet.opik.infrastructure;

import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Rejects requests whose {@code Content-Length} exceeds the configured limit with 413, before the
 * body is read. Fast-fails oversized ingestion at the request boundary so the JSON parser never
 * starts. The header reflects the on-the-wire size; requests without a Content-Length, or with a
 * small compressed body that decompresses large, are still bounded by the Jackson document-length
 * constraint enforced during parsing.
 */
@Slf4j
@RequiredArgsConstructor
public class RequestSizeLimitFilter implements ContainerRequestFilter {

    private final long maxRequestSizeBytes;

    @Inject
    public RequestSizeLimitFilter(JacksonConfig jacksonConfig) {
        this(jacksonConfig.getMaxRequestSizeBytes());
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String headerValue = requestContext.getHeaderString(HttpHeaders.CONTENT_LENGTH);

        // No Content-Length (e.g. chunked transfer) is legitimate; the body is still bounded by the
        // Jackson document-length constraint during parsing.
        if (headerValue == null) {
            return;
        }

        // Read the header as a long: ContainerRequestContext.getLength() returns an int and yields -1
        // for bodies larger than Integer.MAX_VALUE (~2GB), which would let the largest payloads slip
        // through. A present-but-malformed, multi-valued, or negative Content-Length is invalid HTTP
        // framing: fail closed with 400 rather than treating it as "unknown" and waving it past.
        long contentLength;
        try {
            contentLength = Long.parseLong(headerValue.trim());
        } catch (NumberFormatException e) {
            abort(requestContext, Response.Status.BAD_REQUEST, "Invalid Content-Length header");
            return;
        }
        if (contentLength < 0) {
            abort(requestContext, Response.Status.BAD_REQUEST, "Invalid Content-Length header");
            return;
        }

        if (contentLength > maxRequestSizeBytes) {
            log.warn("Rejecting request with Content-Length '{}' exceeding limit '{}' bytes",
                    contentLength, maxRequestSizeBytes);
            abort(requestContext, Response.Status.REQUEST_ENTITY_TOO_LARGE,
                    "Request body exceeds the maximum allowed size of %d bytes".formatted(maxRequestSizeBytes));
        }
    }

    private void abort(ContainerRequestContext requestContext, Response.Status status, String message) {
        requestContext.abortWith(Response.status(status)
                .entity(new ErrorMessage(status.getStatusCode(), message))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .build());
    }
}
