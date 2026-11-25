package com.comet.opik.domain.cost;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum ModelPriceHistoryAction implements HasValue {
    CREATED("created"),
    UPDATED("updated"),
    DELETED("deleted");

    @JsonValue
    private final String value;

    @JsonCreator
    public static ModelPriceHistoryAction fromString(String value) {
        return Arrays.stream(values())
                .filter(action -> action.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model price history action '%s'"
                        .formatted(value)));
    }
}
