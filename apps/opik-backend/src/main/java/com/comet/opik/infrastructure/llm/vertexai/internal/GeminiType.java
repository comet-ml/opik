package com.comet.opik.infrastructure.llm.vertexai.internal;

public enum GeminiType {
    STRING,
    NUMBER,
    INTEGER,
    BOOLEAN,
    ARRAY,
    OBJECT;

    public String toString() {
        return this.name().toLowerCase();
    }
}