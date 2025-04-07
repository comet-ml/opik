package com.comet.opik.infrastructure.json;

import com.comet.opik.utils.JsonUtils;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

@Provider
@Produces(MediaType.APPLICATION_OCTET_STREAM)
public class ErrorMessageBodyWriter implements MessageBodyWriter<ErrorMessage> {

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return ErrorMessage.class.isAssignableFrom(type);
    }

    @Override
    public void writeTo(ErrorMessage errorMessage, Class<?> type, Type genericType, Annotation[] annotations,
            MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream)
            throws IOException, WebApplicationException {
        entityStream.write(JsonUtils.writeValueAsString(errorMessage).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public long getSize(ErrorMessage errorMessage, Class<?> type, Type genericType, Annotation[] annotations,
            MediaType mediaType) {
        return JsonUtils.writeValueAsString(errorMessage).getBytes(StandardCharsets.UTF_8).length;
    }
}
