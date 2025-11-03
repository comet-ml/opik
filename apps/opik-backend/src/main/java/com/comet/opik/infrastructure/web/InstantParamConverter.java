package com.comet.opik.infrastructure.web;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * JAX-RS ParamConverter for automatic Instant conversion from query parameters.
 * Supports both ISO-8601 format (e.g., "2024-01-01T00:00:00Z") and milliseconds since epoch.
 */
@Provider
@Slf4j
public class InstantParamConverter implements ParamConverterProvider {

    @Override
    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
        if (rawType != Instant.class) {
            return null;
        }

        return (ParamConverter<T>) new InstantConverter();
    }

    private static class InstantConverter implements ParamConverter<Instant> {

        @Override
        public Instant fromString(String value) {
            if (value == null || value.isEmpty()) {
                return null;
            }

            try {
                // Try parsing as ISO-8601 format first
                return Instant.parse(value);
            } catch (DateTimeParseException exception) {
                log.debug("Failed to parse '{}' as ISO-8601, attempting to parse as milliseconds since epoch", value,
                        exception);
                try {
                    // Fall back to parsing as milliseconds since epoch
                    long epochMillis = Long.parseLong(value);
                    return Instant.ofEpochMilli(epochMillis);
                } catch (NumberFormatException numberFormatException) {
                    throw new BadRequestException(
                            "Invalid instant format: '%s'. Expected ISO-8601 (e.g., 2024-01-01T00:00:00Z) or milliseconds since epoch"
                                    .formatted(value));
                }
            }
        }

        @Override
        public String toString(Instant value) {
            return value != null ? value.toString() : null;
        }
    }
}
