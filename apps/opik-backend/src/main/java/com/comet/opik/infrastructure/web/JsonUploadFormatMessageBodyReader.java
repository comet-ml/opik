package com.comet.opik.infrastructure.web;

import com.comet.opik.api.JsonUploadFormat;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * Reads a {@link JsonUploadFormat} value from a multipart form field, accepting any
 * case (the OpenAPI spec advertises lowercase {@code json}/{@code jsonl} via
 * {@code @JsonValue}, while Jersey's default {@code EnumMessageProvider} would
 * fall back to {@code Enum.valueOf} and only accept the uppercase enum literal
 * names).
 *
 * <p>Why a {@code MessageBodyReader} and not a {@code ParamConverter}: Jersey's
 * multipart pipeline ({@code FormDataParamValueParamProvider}) reads non-file
 * form fields through the message-body pipeline, not through
 * {@code ParamConverter}. A more specific {@code MessageBodyReader} for
 * {@code JsonUploadFormat} wins against the generic {@code EnumMessageProvider}.
 */
@Provider
@Consumes(MediaType.WILDCARD)
public class JsonUploadFormatMessageBodyReader implements MessageBodyReader<JsonUploadFormat> {

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return JsonUploadFormat.class.equals(type);
    }

    @Override
    public JsonUploadFormat readFrom(Class<JsonUploadFormat> type, Type genericType, Annotation[] annotations,
            MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream)
            throws IOException {
        String value = new String(entityStream.readAllBytes(), StandardCharsets.UTF_8).trim();
        try {
            return JsonUploadFormat.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}