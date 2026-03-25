package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * The origin of a trace or span, set at creation time.
 * <p>
 * The {@code unknown} value (Enum8 = 0) is the ClickHouse DEFAULT for rows that predate this field.
 * It is intentionally absent from this enum so it cannot be explicitly ingested via the API.
 * </p>
 **/
@Getter
@RequiredArgsConstructor
public enum Source {

    SDK("sdk"),
    EXPERIMENT("experiment"),
    PLAYGROUND("playground"),
    OPTIMIZATION("optimization"),
    ;

    /** The ClickHouse storage value for rows that predate source tracking. Not an ingestion option. */
    public static final String UNKNOWN_VALUE = "unknown";

    @JsonValue
    private final String value;

    @JsonCreator
    public static Optional<Source> fromString(String value) {
        return Arrays.stream(Source.values())
                .filter(v -> v.getValue().equals(value))
                .findFirst();
    }

    /**
     * Returns the additional DB value to include alongside the given filter value in equality
     * filters, to capture legacy rows that predate source tracking.
     * <p>
     * Only {@code sdk} includes legacy {@code unknown} rows, since SDK was the predominant
     * ingestion path before source tracking was introduced. Playground, experiment, and other
     * sources did not exist at that time, so unknown rows must not be attributed to them.
     * </p>
     */
    public static Optional<String> legacyFallbackDbValue(String filterValue) {
        if (SDK.getValue().equals(filterValue)) {
            return Optional.of(UNKNOWN_VALUE);
        }
        return Optional.empty();
    }
}
