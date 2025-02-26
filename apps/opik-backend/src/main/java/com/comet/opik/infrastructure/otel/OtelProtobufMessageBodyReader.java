package com.comet.opik.infrastructure.otel;

import com.google.protobuf.Message;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

@Provider
@Consumes("application/x-protobuf")
public class OtelProtobufMessageBodyReader<T extends Message> implements MessageBodyReader<T> {

    @Override
    public boolean isReadable(Class<?> type, Type genericType,
            Annotation[] annotations, MediaType mediaType) {
        return Message.class.isAssignableFrom(type);
    }

    @Override
    public T readFrom(Class<T> type, Type genericType, Annotation[] annotations,
            MediaType mediaType, MultivaluedMap<String, String> httpHeaders,
            InputStream entityStream) throws IOException {
        try {
            // Find the `parseFrom(InputStream)` method in the Protobuf class
            Method parseFrom = type.getMethod("parseFrom", InputStream.class);
            return (T) parseFrom.invoke(null, entityStream);
        } catch (Exception e) {
            throw new IOException("Failed to parse Protobuf message", e);
        }
    }
}
