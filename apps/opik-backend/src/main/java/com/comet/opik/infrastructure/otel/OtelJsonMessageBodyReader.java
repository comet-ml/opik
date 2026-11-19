package com.comet.opik.infrastructure.otel;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
        } catch (UncheckedIOException e) {
            // A body that is not valid JSON is the client's error: answer 400 so exporters don't retry it.
            if (e.getCause() instanceof JsonProcessingException invalid) {
                throw badRequest(invalid.getOriginalMessage(), invalid);
            }
            throw new IOException("Failed to parse JSON to Protobuf", e.getCause());
        } catch (InvalidProtocolBufferException e) {
            // Valid JSON that does not fit the OTLP message, such as an unknown field or a wrong type.
            throw badRequest(e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("Failed to parse JSON to Protobuf", e);
        }
    }

    private static BadRequestException badRequest(String error, Exception cause) {
        return new BadRequestException("Invalid JSON message, error: '%s'".formatted(error), cause);
    }
}
