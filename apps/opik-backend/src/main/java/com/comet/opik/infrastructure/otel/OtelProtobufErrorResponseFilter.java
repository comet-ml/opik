package com.comet.opik.infrastructure.otel;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Providers;
import lombok.RequiredArgsConstructor;

/**
 * Answers every error on a protobuf request in protobuf, as OTLP/HTTP expects a {@code google.rpc.Status}. Dropwizard's
 * {@code LoggingExceptionMapper}, behind both Opik exception mappers, and some mappers and request filters force
 * {@code application/json} on their error: the auth, usage-limit, size-limit and malformed-body errors exporters hit
 * most. Re-typing those responses lets the protobuf error writers in this package encode them. Only an entity one of
 * those writers accepts is re-typed, so this can never turn an error into a missing-writer 500.
 */
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OtelProtobufErrorResponseFilter implements ContainerResponseFilter {

    private static final MediaType PROTOBUF = MediaType.valueOf("application/x-protobuf");

    private final Provider<Providers> providers;

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (response.getStatus() < 400 || !response.hasEntity()) {
            return;
        }
        var requestType = request.getMediaType();
        if (requestType == null || !requestType.isCompatible(PROTOBUF)) {
            return;
        }
        var writer = providers.get().getMessageBodyWriter(response.getEntityClass(), response.getEntityType(),
                response.getEntityAnnotations(), PROTOBUF);
        if (writer != null) {
            response.getHeaders().putSingle(HttpHeaders.CONTENT_TYPE, PROTOBUF);
        }
    }
}
