package com.comet.opik.api.runner;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BridgeCommandType {

    READ_FILE("ReadFile"),
    WRITE_FILE("WriteFile"),
    EDIT_FILE("EditFile"),
    LIST_FILES("ListFiles"),
    SEARCH_FILES("SearchFiles");

    @JsonValue
    private final String value;

    public boolean isWriteCommand() {
        return this == WRITE_FILE || this == EDIT_FILE;
    }
}
