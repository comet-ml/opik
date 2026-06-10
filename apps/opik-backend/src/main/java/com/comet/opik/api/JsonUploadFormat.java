package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum JsonUploadFormat {
    JSON("json"),
    JSONL("jsonl");

    @JsonValue
    private final String value;

    @JsonCreator
    public static JsonUploadFormat fromString(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(format -> format.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown JSON upload format '%s'. Supported values: json, jsonl".formatted(value)));
    }
}