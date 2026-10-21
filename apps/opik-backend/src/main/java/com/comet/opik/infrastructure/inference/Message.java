package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Builder(toBuilder = true)
public record Message(@NonNull Role role, @NonNull String content) {

    @Getter
    @RequiredArgsConstructor
    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant");

        @JsonValue
        private final String value;
    }
}
