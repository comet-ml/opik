package com.comet.opik.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EntityType {
    TRACE("trace", "traces"),
    SPAN("span", "spans"),
    THREAD("thread", "threads"),

    ;

    private final String type;
    private final String tableName;
}
