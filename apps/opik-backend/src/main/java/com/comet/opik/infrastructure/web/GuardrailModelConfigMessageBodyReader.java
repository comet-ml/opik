package com.comet.opik.infrastructure.web;

import com.comet.opik.api.GuardrailModelConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
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

/**
 * Reads a guardrail model's config form part as JSON, whatever its Content-Type. Browsers and curl send a text part
 * with none, which Jersey takes as {@code text/plain}, and Jackson's reader only takes JSON types. Without this reader,
 * Jersey falls back to the record's single-String constructor, so the whole JSON text becomes the prompt.
 */
@Provider
@Consumes(MediaType.WILDCARD)
public class GuardrailModelConfigMessageBodyReader implements MessageBodyReader<GuardrailModelConfig> {

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return GuardrailModelConfig.class.equals(type);
    }

    @Override
    public GuardrailModelConfig readFrom(Class<GuardrailModelConfig> type, Type genericType, Annotation[] annotations,
            MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream)
            throws IOException {
        try {
            return JsonUtils.getMapper().readValue(entityStream, GuardrailModelConfig.class);
        } catch (JsonProcessingException exception) {
            // Jersey turns a reader's IOException into a bare 400, so the message names the part.
            throw new BadRequestException(
                    "Unable to process the config part as JSON. " + exception.getOriginalMessage());
        }
    }
}
