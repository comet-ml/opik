package com.comet.opik.api.runner;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

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

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == TIMED_OUT;
    }
}
