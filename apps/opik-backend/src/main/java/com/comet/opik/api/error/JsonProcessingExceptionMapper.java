package com.comet.opik.api.error;

import com.comet.opik.infrastructure.auth.RequestContext;
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
        // A StreamConstraintsException is the document-/string-length size guard tripping mid-parse
        // (OPIK-7334) — an EXPECTED client rejection, already surfaced via the ingestion size-guard
        // metric. Keep it at DEBUG so normal oversized-payload traffic doesn't flood INFO logs.
        //
        // It rarely arrives as the thrown type: when the overrun trips while binding a bean property
        // (the common case — a well-formed SpanBatch/TraceBatch whose input/output/metadata is
        // oversized), Jackson wraps it in a JsonMappingException carrying the reference chain, so a
        // plain `instanceof` check misses it and the metric under-counts exactly the real-world case
        // the dashboard monitors. Walk the cause chain to find the underlying constraint.
        StreamConstraintsException streamConstraintsException = findStreamConstraint(exception);
        Response.Status status;
        if (streamConstraintsException != null) {
            // A size-guard trip is a payload-too-large rejection, not malformed JSON, so surface it as
            // 413 REQUEST_ENTITY_TOO_LARGE — consistent with RequestSizeLimitFilter's pre-parse 413 —
            // rather than 400. It is an EXPECTED client rejection, already surfaced via the ingestion
            // size-guard metric, so keep it at DEBUG so oversized-payload traffic doesn't flood INFO.
            log.debug("Ingestion size guard rejected a request", exception);
            sizeGuardMetrics.recordStreamConstraintRejection(streamConstraintsException, uriInfo.get(), requestContext);
            status = Response.Status.REQUEST_ENTITY_TOO_LARGE;
        } else {
            // Genuine malformed JSON: log the exception itself (not just its message) so the type and
            // stack trace are available to diagnose the rare, unexpected parse failure.
            log.info("Deserialization exception", exception);
            status = Response.Status.BAD_REQUEST;
        }

        return Response.status(status)
                .entity(new ErrorMessage(status.getStatusCode(), "Unable to process JSON. " + exception.getMessage()))
                .build();
    }

    /**
     * Return the {@link StreamConstraintsException} the given throwable is or wraps, or {@code null} if
     * none is present. Jackson raises the size-guard overrun at the parser level but re-wraps it in a
     * {@link com.fasterxml.jackson.databind.JsonMappingException} (with the bean reference chain) when
     * it trips during databinding, so the constraint is usually a cause rather than the thrown type.
     * The walk is bounded and self-reference-safe to avoid looping on a malformed cause chain.
     */
    private static StreamConstraintsException findStreamConstraint(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof StreamConstraintsException streamConstraintsException) {
                return streamConstraintsException;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return null;
    }
}
