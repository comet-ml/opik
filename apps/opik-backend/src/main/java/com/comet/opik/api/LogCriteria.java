package com.comet.opik.api;

import lombok.Builder;
import lombok.NonNull;

import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.LogItem.LogLevel;

@Builder
public record LogCriteria(
        @NonNull String workspaceId,
        UUID entityId,
        LogLevel level,
        Integer size,
        Map<String, String> markers) {
}
