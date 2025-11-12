package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TemplateStructure {
    STRING("string"),
    CHAT("chat");

    private final String value;

    TemplateStructure(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static TemplateStructure fromString(String value) {
        if (value == null) {
            return STRING; // Default to STRING if null
        }
        for (TemplateStructure structure : TemplateStructure.values()) {
            if (structure.value.equalsIgnoreCase(value)) {
                return structure;
            }
        }
        throw new IllegalArgumentException("Unknown template structure: " + value);
    }
}
