package com.comet.opik.api.retention;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum RetentionPeriod implements HasValue {

    SHORT_14D("short_14d", 14),
    BASE_60D("base_60d", 60),
    EXTENDED_400D("extended_400d", 400),
    UNLIMITED("unlimited", null);

    @JsonValue
    private final String value;
    private final Integer days;

    @JsonCreator
    public static RetentionPeriod fromString(String value) {
        return Arrays.stream(values())
                .filter(period -> period.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown retention period '%s'".formatted(value)));
    }
}
