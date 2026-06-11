package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.ws.rs.BadRequestException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum AgentInsightsSortBy {
    LAST_SEEN("last_seen"),
    TOTAL_OCCURRENCES("total_occurrences");

    @JsonValue
    private final String value;

    // BadRequestException so an invalid query param value yields 400 instead of JAX-RS's default 404
    public static AgentInsightsSortBy fromString(String value) {
        return Arrays.stream(values())
                .filter(sortBy -> sortBy.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Unknown sort field: '%s'".formatted(value)));
    }
}
