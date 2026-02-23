package com.comet.opik.infrastructure.web;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@Provider
@Slf4j
public class InstantParamConverter extends AbstractParamConverterProvider<Instant> {

    public InstantParamConverter() {
        super(Instant.class);
    }

    @Override
    protected Instant parse(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            log.debug("Failed to parse '{}' as ISO-8601, attempting to parse as milliseconds since epoch", value,
                    exception);
            try {
                long epochMillis = Long.parseLong(value);
                return Instant.ofEpochMilli(epochMillis);
            } catch (NumberFormatException numberFormatException) {
                throw new BadRequestException(
                        "Invalid instant format: '%s'. Expected ISO-8601 (e.g., 2024-01-01T00:00:00Z) or milliseconds since epoch"
                                .formatted(value));
            }
        }
    }
}
