package com.comet.opik.infrastructure.bi;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
enum Metadata {

    ANONYMOUS_ID("anonymous_id"),
    FIRST_TRACE_CREATED("first_trace_created"),
    ;

    private final String value;
}
