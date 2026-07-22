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
        // A StreamConstraintsException - often wrapped in a JsonMappingException during bean binding, so
        // walk the cause chain - is the ingestion size guard tripping mid-parse (OPIK-7334): a
        // payload-too-large rejection (413, like RequestSizeLimitFilter). Anything else is malformed JSON.
        StreamConstraintsException streamConstraintsException = findStreamConstraint(exception);
        Response.Status status;
        String clientMessage;
        if (streamConstraintsException != null) {
            log.debug("Ingestion size guard rejected a request", exception); // expected; already on the metric
            sizeGuardMetrics.recordStreamConstraintRejection(streamConstraintsException, uriInfo.get(), requestContext);
            status = Response.Status.REQUEST_ENTITY_TOO_LARGE;
            clientMessage = "Request payload exceeds the maximum allowed size.";
        } else {
            log.info("Deserialization exception for workspace {}",
                    ErrorMetricsResolver.workspaceId(requestContext), exception);
            status = Response.Status.BAD_REQUEST;
            clientMessage = "Unable to process the request body: it is not valid JSON.";
        }

        // Stable, generic message: exception.getMessage() can carry parser internals and fragments of the
        // client's payload, so the detail stays in the server log above, not the HTTP response.
        return Response.status(status)
                .entity(new ErrorMessage(status.getStatusCode(), clientMessage))
                .build();
    }

    // Real exception cause chains are only a handful deep; this cap is far beyond any legitimate chain
    // yet stops a pathological cause cycle (A -> B -> A, which Throwable.initCause permits — it rejects
    // only a direct self-cause) from spinning the walk forever and pinning the request thread.
    private static final int MAX_CAUSE_DEPTH = 50;

    /**
     * The {@link StreamConstraintsException} this throwable is or wraps (Jackson re-wraps it in a
     * JsonMappingException during bean binding), or {@code null}. The walk is bounded by
     * {@link #MAX_CAUSE_DEPTH} so a cyclic cause chain cannot hang the request thread.
     */
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
