package com.comet.opik.api.error;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JsonProcessingExceptionMapper implements ExceptionMapper<JsonProcessingException> {

    private final IngestionSizeGuardMetrics sizeGuardMetrics;
    private final Provider<RequestContext> requestContext;
    private final Provider<UriInfo> uriInfo;

    @Inject
    public JsonProcessingExceptionMapper(IngestionSizeGuardMetrics sizeGuardMetrics,
            Provider<RequestContext> requestContext, Provider<UriInfo> uriInfo) {
        this.sizeGuardMetrics = sizeGuardMetrics;
        this.requestContext = requestContext;
        this.uriInfo = uriInfo;
    }

    @Override
    public Response toResponse(JsonProcessingException exception) {
        // A StreamConstraintsException (often wrapped by Jackson during bean binding, hence the cause walk)
        // is the size guard tripping mid-parse -> 413; anything else is malformed JSON -> 400.
        StreamConstraintsException streamConstraintsException = findStreamConstraint(exception);
        Response.Status status;
        String clientMessage;
        if (streamConstraintsException != null) {
            log.debug("Ingestion size guard rejected a request", exception); // expected; already on the metric
            sizeGuardMetrics.recordStreamConstraintRejection(streamConstraintsException, uriInfo.get(), requestContext);
            status = Response.Status.REQUEST_ENTITY_TOO_LARGE;
            // Redacted: the size-guard message exposes internal limits/Jackson internals (detail is in the log).
            clientMessage = "Request payload exceeds the maximum allowed size.";
        } else {
            log.info("Deserialization exception for workspace {}",
                    ErrorMetricsResolver.workspaceId(requestContext), exception);
            status = Response.Status.BAD_REQUEST;
            // Keep the parser detail (the caller's own payload, not internal limits) - a long-standing contract
            // many endpoints assert; do NOT genericize this branch (that broke ~8 test suites once).
            clientMessage = "Unable to process JSON. " + exception.getMessage();
        }

        return Response.status(status)
                .entity(new ErrorMessage(status.getStatusCode(), clientMessage))
                .build();
    }

    // Bounds the cause walk so a pathological cause cycle (A -> B -> A) can't spin forever and pin the
    // request thread; 50 is far beyond any real chain.
    private static final int MAX_CAUSE_DEPTH = 50;

    /** The {@link StreamConstraintsException} this throwable is or wraps, or {@code null}. */
    private static StreamConstraintsException findStreamConstraint(Throwable throwable) {
        Throwable cause = throwable;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; cause = cause.getCause(), depth++) {
            if (cause instanceof StreamConstraintsException streamConstraintsException) {
                return streamConstraintsException;
            }
        }
        return null;
    }
}
