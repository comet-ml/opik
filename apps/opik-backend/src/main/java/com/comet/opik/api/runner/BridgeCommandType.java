package com.comet.opik.api.runner;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BridgeCommandType {

    READ_FILE("read_file"),
    WRITE_FILE("write_file"),
    EDIT_FILE("edit_file"),
    LIST_FILES("list_files"),
    SEARCH_FILES("search_files");

    @JsonValue
    private final String value;

    public boolean isWriteCommand() {
        return this == WRITE_FILE || this == EDIT_FILE;
    }
}
