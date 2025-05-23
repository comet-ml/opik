package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public enum VisibilityMode {

    DEFAULT("default"),
    HIDDEN("hidden"),
    ;

    @JsonValue
    private final String value;

    public static VisibilityMode fromString(String value) {
        Optional<VisibilityMode> type = Arrays.stream(VisibilityMode.values())
                .filter(v -> v.getValue().equals(value))
                .findFirst();

        if (type.isPresent()) {
            return type.get();
        } else if ("unknown".equals(value)) {
            return null;
        }

        throw new IllegalArgumentException("Invalid SpanType: " + value);
    }
}
