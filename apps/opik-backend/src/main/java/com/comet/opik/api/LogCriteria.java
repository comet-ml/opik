package com.comet.opik.api;

import lombok.Builder;

import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.LogItem.LogLevel;

@Builder
public record LogCriteria(
        UUID entityId,
        LogLevel level,
        Integer size,
        Integer page,
        Map<String, String> markers) {
}
