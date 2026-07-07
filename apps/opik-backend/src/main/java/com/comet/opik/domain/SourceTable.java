package com.comet.opik.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * The table a deletion-events bridge row was captured from, stored as the {@code source_table} column.
 */
@Getter
@RequiredArgsConstructor
public enum SourceTable {

    TRACES("traces");

    private final String value;

    public static Optional<SourceTable> fromString(String value) {
        return Arrays.stream(values())
                .filter(sourceTable -> sourceTable.value.equalsIgnoreCase(value))
                .findFirst();
    }

    public static SourceTable fromStringOrThrow(String value) {
        return fromString(value)
                .orElseThrow(() -> new IllegalStateException("Unknown source table: '%s'".formatted(value)));
    }
}
