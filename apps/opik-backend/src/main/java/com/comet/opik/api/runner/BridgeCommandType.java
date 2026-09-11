package com.comet.opik.api.runner;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum BridgeCommandType {

    READ_FILE("ReadFile"),
    WRITE_FILE("WriteFile"),
    EDIT_FILE("EditFile"),
    LIST_FILES("ListFiles"),
    SEARCH_FILES("SearchFiles"),
    EXEC("Exec");

    @JsonValue
    private final String value;

    @JsonCreator
    public static BridgeCommandType fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown BridgeCommandType: " + value));
    }

    public boolean isWriteCommand() {
        return this == WRITE_FILE || this == EDIT_FILE || this == EXEC;
    }
}
