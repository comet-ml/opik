package com.comet.opik.infrastructure.otel;

import com.comet.opik.api.error.ErrorMessage;
import com.google.rpc.Status;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * Writes an error answering a protobuf request as the {@code google.rpc.Status} OTLP/HTTP expects for a 4xx or 5xx,
 * whenever the request accepts {@code application/x-protobuf}. Without a protobuf writer Jersey turns the error into a
 * 500, which exporters retry instead of dropping a batch that can never succeed. The writer is gated on the request
 * body being protobuf, as OTLP answers in the request's encoding: without the gate a JSON request accepting any type,
 * on any resource that declares no {@code @Produces}, would get a protobuf error. The HTTP status carries the outcome,
 * so only the message is set.
 */
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Produces("application/x-protobuf")
public class OtelProtobufErrorMessageBodyWriter implements MessageBodyWriter<ErrorMessage> {

    private static final MediaType PROTOBUF = MediaType.valueOf("application/x-protobuf");

    private final Provider<HttpHeaders> requestHeaders;

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return ErrorMessage.class.isAssignableFrom(type) && isProtobufRequest(requestHeaders.get());
    }

    @Override
    public void writeTo(ErrorMessage error, Class<?> type, Type genericType, Annotation[] annotations,
            MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream)
            throws IOException {
        Status.newBuilder()
                .setMessage(String.join("; ", error.errors()))
                .build()
                .writeTo(entityStream);
    }

    static boolean isProtobufRequest(HttpHeaders requestHeaders) {
        var requestType = requestHeaders.getMediaType();
        return requestType != null && requestType.isCompatible(PROTOBUF);
    }
}
