package com.comet.opik.infrastructure.otel;

import com.google.rpc.Status;
import io.dropwizard.jersey.validation.ValidationErrorMessage;
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
 * Writes a {@link ValidationErrorMessage} as a {@code google.rpc.Status}, see {@link OtelProtobufErrorMessageBodyWriter}.
 */
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Produces("application/x-protobuf")
public class OtelProtobufValidationErrorMessageBodyWriter implements MessageBodyWriter<ValidationErrorMessage> {

    private final Provider<HttpHeaders> requestHeaders;

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return ValidationErrorMessage.class.isAssignableFrom(type)
                && OtelProtobufErrorMessageBodyWriter.isProtobufRequest(requestHeaders.get());
    }

    @Override
    public void writeTo(ValidationErrorMessage error, Class<?> type, Type genericType, Annotation[] annotations,
            MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream)
            throws IOException {
        Status.newBuilder()
                .setMessage(String.join("; ", error.getErrors()))
                .build()
                .writeTo(entityStream);
    }
}
