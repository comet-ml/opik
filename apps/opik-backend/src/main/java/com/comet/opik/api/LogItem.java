package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LogItem(
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant timestamp,
        @JsonIgnore String workspaceId,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID ruleId,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) LogLevel level,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String message,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Map<String, String> markers) {

    public enum LogLevel {
        INFO,
        WARN,
        ERROR,
        DEBUG,
        TRACE
    }

    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LogPage(List<LogItem> content, int page, int size, long total) implements Page<LogItem> {

        public static LogPage empty(int page) {
            return new LogPage(List.of(), page, 0, 0);
        }
    }
}
