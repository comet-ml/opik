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
        if (exception instanceof StreamConstraintsException streamConstraintsException) {
            log.debug("Ingestion size guard rejected a request", streamConstraintsException);
            sizeGuardMetrics.recordStreamConstraintRejection(streamConstraintsException, uriInfo.get(), requestContext);
        } else {
            // Genuine malformed JSON: log the exception itself (not just its message) so the type and
            // stack trace are available to diagnose the rare, unexpected parse failure.
            log.info("Deserialization exception", exception);
        }

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(),
                        "Unable to process JSON. " + exception.getMessage()))
                .build();
    }
}
