package com.comet.opik.api.error;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

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
        // A StreamConstraintsException (often wrapped by Jackson during binding) is a stream-read limit
        // tripping mid-parse. getThrowableList is cycle-safe, so no manual bound is needed. A size limit
        // (document/string length) -> 413; a structural limit (nesting/number) or anything else -> 400.
        StreamConstraintsException streamConstraint = ExceptionUtils.getThrowableList(exception).stream()
                .filter(StreamConstraintsException.class::isInstance)
                .map(StreamConstraintsException.class::cast)
                .findFirst()
                .orElse(null);

        Response.Status status;
        String clientMessage;
        if (streamConstraint != null) {
            sizeGuardMetrics.recordStreamConstraintRejection(streamConstraint, uriInfo.get(), requestContext);
            String guard = IngestionSizeGuardMetrics.classifyStreamConstraint(streamConstraint);
            if (IngestionSizeGuardMetrics.GUARD_DOCUMENT_LENGTH.equals(guard)
                    || IngestionSizeGuardMetrics.GUARD_STRING_LENGTH.equals(guard)) {
                log.debug("Ingestion size guard rejected a request", exception); // expected; already on the metric
                status = Response.Status.REQUEST_ENTITY_TOO_LARGE;
                clientMessage = "Request payload exceeds the maximum allowed size."; // redacted; detail in the log
            } else {
                // A structural limit (nesting/number depth), not an oversize -> 400; kept generic since the
                // message also carries Jackson internals.
                log.info("Rejecting a request that violates a JSON structural limit for workspace '{}'",
                        ErrorMetricsResolver.workspaceId(requestContext));
                status = Response.Status.BAD_REQUEST;
                clientMessage = "Unable to process JSON. The request exceeds an allowed structural limit.";
            }
        } else {
            // Redacted summary only: the exception message carries the caller's own body content (potential
            // PII), so log the type + workspace, never the throwable (SKILL.md "Never Log PII").
            log.info("Deserialization exception for workspace '{}': {}",
                    ErrorMetricsResolver.workspaceId(requestContext), exception.getClass().getSimpleName());
            status = Response.Status.BAD_REQUEST;
            // Keep the parser detail (the caller's own payload, not internal limits) - a long-standing contract
            // many endpoints assert; do NOT genericize this branch (that broke ~8 test suites once).
            clientMessage = "Unable to process JSON. " + exception.getMessage();
        }

        // Force JSON: ingestion endpoints negotiate non-JSON (e.g. OTel protobuf) with no writer for
        // ErrorMessage, so without an explicit type the error fails to serialize and surfaces as a 500
        // (matches InvalidUUIDExceptionMapper and RequestSizeLimitFilter).
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorMessage(status.getStatusCode(), clientMessage))
                .build();
    }
}
