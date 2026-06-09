package com.comet.opik.domain.mcpoauth;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum RevokedReason implements HasValue {

    ROTATED("rotated"),
    REUSE("reuse"),
    CLIENT_REQUEST("client_request");

    @JsonValue
    private final String value;

    @JsonCreator
    public static RevokedReason fromString(String value) {
        return Arrays.stream(values())
                .filter(reason -> reason.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown revoked reason '%s'".formatted(value)));
    }
}
