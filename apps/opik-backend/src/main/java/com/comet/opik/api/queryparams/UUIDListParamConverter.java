package com.comet.opik.api.queryparams;

import jakarta.ws.rs.ext.ParamConverter;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static java.util.stream.Collectors.joining;

public class UUIDListParamConverter implements ParamConverter<List<UUID>> {

    @Override
    public List<UUID> fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return List.of(); // Return an empty list if no value is provided
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(UUID::fromString)
                .toList();
    }

    @Override
    public String toString(List<UUID> value) {
        return value.stream()
                .map(UUID::toString)
                .collect(joining(","));
    }
}
