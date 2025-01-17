package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum ScoreSource {
    UI("ui"),
    SDK("sdk"),
    ONLINE_SCORING("online_scoring");

    @JsonValue
    private final String value;

    public static ScoreSource fromString(String source) {
        return Arrays.stream(values()).filter(v -> v.value.equals(source)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown source: " + source));
    }
}
