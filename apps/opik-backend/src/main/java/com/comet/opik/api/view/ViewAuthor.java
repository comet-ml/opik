package com.comet.opik.api.view;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum ViewAuthor implements HasValue {

    USER("user"),
    OLLIE("ollie"),
    AGENT("agent");

    @JsonValue
    private final String value;

    public static ViewAuthor orDefault(ViewAuthor author) {
        return author == null ? USER : author;
    }

    @JsonCreator
    public static ViewAuthor fromString(String value) {
        return Arrays.stream(values())
                .filter(item -> item.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown view author '%s'".formatted(value)));
    }
}
