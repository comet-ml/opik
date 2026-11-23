package com.comet.opik.infrastructure.otel;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Answers every error on a protobuf request in protobuf, as OTLP/HTTP expects a {@code google.rpc.Status}. Dropwizard's
 * {@code LoggingExceptionMapper}, behind both Opik exception mappers, and some mappers and request filters force
 * {@code application/json} on their error: the auth, usage-limit, size-limit and malformed-body errors exporters hit
 * most. Re-typing those responses lets the protobuf error writers in this package encode them. Only an entity one of
 * those writers accepts is re-typed, so this can never turn an error into a missing-writer 500 or label a non-protobuf
 * body as protobuf.
 */
public class OtelProtobufErrorResponseFilter implements ContainerResponseFilter {

    private static final MediaType PROTOBUF = MediaType.valueOf("application/x-protobuf");

    private static final List<Class<?>> ERROR_ENTITY_CLASSES = List.of(
            OtelProtobufErrorMessageBodyWriter.ENTITY_CLASS,
            OtelProtobufDropwizardErrorMessageBodyWriter.ENTITY_CLASS,
            OtelProtobufValidationErrorMessageBodyWriter.ENTITY_CLASS);

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (response.getStatus() < 400 || !response.hasEntity()) {
            return;
        }
        var requestType = request.getMediaType();
        if (requestType == null || !requestType.isCompatible(PROTOBUF)) {
            return;
        }
        var entityClass = response.getEntityClass();
        if (ERROR_ENTITY_CLASSES.stream().anyMatch(errorClass -> errorClass.isAssignableFrom(entityClass))) {
            response.getHeaders().putSingle(HttpHeaders.CONTENT_TYPE, PROTOBUF);
        }
    }
}
