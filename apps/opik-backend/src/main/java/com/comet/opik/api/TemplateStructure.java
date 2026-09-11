package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TemplateStructure {
    TEXT("text"),
    CHAT("chat");

    @JsonValue
    private final String value;

    @JsonCreator
    public static TemplateStructure fromString(String value) {
        if (value == null) {
            return TEXT; // Default to TEXT if null
        }
        for (TemplateStructure structure : TemplateStructure.values()) {
            if (structure.value.equalsIgnoreCase(value)) {
                return structure;
            }
        }
        // Default to TEXT for unknown values (backward compatibility)
        return TEXT;
    }
}
