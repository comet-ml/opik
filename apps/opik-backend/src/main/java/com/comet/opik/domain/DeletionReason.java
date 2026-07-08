package com.comet.opik.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * Why a row was recorded in the deletion-events bridge, stored as the {@code deletion_reason} column.
 */
@Getter
@RequiredArgsConstructor
public enum DeletionReason {

    USER_REQUEST("user_request");

    private final String value;

    public static Optional<DeletionReason> fromString(String value) {
        return Arrays.stream(values())
                .filter(reason -> reason.value.equalsIgnoreCase(value))
                .findFirst();
    }

    public static DeletionReason fromStringOrThrow(String value) {
        return fromString(value)
                .orElseThrow(() -> new IllegalStateException("Unknown deletion reason: '%s'".formatted(value)));
    }
}
