package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum DatasetItemSource {

    MANUAL("manual"),
    TRACE("trace"),
    SPAN("span"),
    SDK("sdk");

    @JsonValue
    private final String value;

    public static DatasetItemSource fromString(String source) {

        if ("unknown".equals(source)) {
            return null;
        }

        return Arrays.stream(values()).filter(v -> v.value.equals(source)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown dataset source: " + source));
    }
}
