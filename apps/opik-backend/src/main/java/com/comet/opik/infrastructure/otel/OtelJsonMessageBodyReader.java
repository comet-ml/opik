package com.comet.opik.infrastructure.otel;

import com.comet.opik.utils.JsonUtils;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

@Provider
@Consumes(MediaType.APPLICATION_JSON)
public class OtelJsonMessageBodyReader<T extends Message> implements MessageBodyReader<T> {

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
            // Parse JSON to a map
            var jsonNode = JsonUtils.getJsonNodeFromString(entityStream);

            if (jsonNode.isTextual()) {
                jsonNode = JsonUtils.getJsonNodeFromString(jsonNode.asText());
            }

            // Convert JSON to Protobuf using JsonFormat
            Message.Builder builder = (Message.Builder) type.getMethod("newBuilder").invoke(null);
            JsonFormat.parser().merge(jsonNode.toString(), builder);

            return (T) builder.build();
        } catch (Exception e) {
            throw new IOException("Failed to parse JSON to Protobuf", e);
        }
    }
}
