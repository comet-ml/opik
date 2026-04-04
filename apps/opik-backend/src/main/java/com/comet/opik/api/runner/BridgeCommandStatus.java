package com.comet.opik.api.runner;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum BridgeCommandStatus {

    PENDING("pending"),
    PICKED_UP("picked_up"),
    COMPLETED("completed"),
    FAILED("failed"),
    TIMED_OUT("timed_out");

    @JsonValue
    private final String value;

    @JsonCreator
    public static BridgeCommandStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown BridgeCommandStatus: " + value));
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == TIMED_OUT;
    }
}
