package com.comet.opik.infrastructure.otel;

import com.google.rpc.Status;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * Writes Dropwizard's {@link ErrorMessage} as a {@code google.rpc.Status}, see {@link OtelProtobufErrorMessageBodyWriter}.
 */
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Produces("application/x-protobuf")
public class OtelProtobufDropwizardErrorMessageBodyWriter implements MessageBodyWriter<ErrorMessage> {

    static final Class<ErrorMessage> ENTITY_CLASS = ErrorMessage.class;

    private final Provider<HttpHeaders> requestHeaders;

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return ENTITY_CLASS.isAssignableFrom(type)
                && OtelProtobufErrorMessageBodyWriter.isProtobufRequest(requestHeaders.get());
    }

    @Override
    public void writeTo(ErrorMessage error, Class<?> type, Type genericType, Annotation[] annotations,
            MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream)
            throws IOException {
        Status.newBuilder()
                .setMessage(StringUtils.isBlank(error.getDetails())
                        ? error.getMessage()
                        : "%s: %s".formatted(error.getMessage(), error.getDetails()))
                .build()
                .writeTo(entityStream);
    }
}
