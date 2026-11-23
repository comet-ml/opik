package com.comet.opik.api.view;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum ViewArea implements HasValue {

    WIDGET("widget");

    @JsonValue
    private final String value;

    @JsonCreator
    public static ViewArea fromString(String value) {
        return Arrays.stream(values())
                .filter(item -> item.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown view area '%s'".formatted(value)));
    }
}
