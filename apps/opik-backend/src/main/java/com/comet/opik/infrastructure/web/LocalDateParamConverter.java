package com.comet.opik.infrastructure.web;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ext.Provider;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@Provider
public class LocalDateParamConverter extends AbstractParamConverterProvider<LocalDate> {

    public LocalDateParamConverter() {
        super(LocalDate.class);
    }

    @Override
    protected LocalDate parse(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BadRequestException(
                    "Invalid date format: '%s'. Expected ISO-8601 date (e.g., 2026-06-11)".formatted(value));
        }
    }
}
