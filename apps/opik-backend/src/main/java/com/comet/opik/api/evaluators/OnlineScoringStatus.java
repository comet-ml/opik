package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Why a rule has not scored a trace (yet). Only recorded for experiment traces, whose scores someone is waiting on.
 * A stored score always supersedes it.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OnlineScoringStatus(
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID ruleId,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Set for a test suite's assertion judge, whose rule id is not stable") boolean suiteAssertion,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) State state,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Reason reason,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "The failed delivery, set while retrying") Long attempt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Deliveries before giving up, set while retrying") Integer maxAttempts,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant updatedAt) {

    @Getter
    @RequiredArgsConstructor
    public enum State {
        RETRYING("retrying"),
        FAILED("failed"),
        SKIPPED("skipped"),
        ;

        @JsonValue
        private final String value;

        @JsonCreator
        public static State fromString(String value) {
            return Arrays.stream(values())
                    .filter(state -> state.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown state '%s'".formatted(value)));
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum Reason {
        NOT_CONFIGURED("not_configured"),
        AUTHENTICATION("authentication"),
        NOT_FOUND("not_found"),
        REJECTED("rejected"),
        RATE_LIMITED("rate_limited"),
        TIMEOUT("timeout"),
        UNREACHABLE("unreachable"),
        SERVER_ERROR("server_error"),
        NO_SCORE("no_score"),
        UNRESOLVED_ARGUMENTS("unresolved_arguments"),
        OTHER("other"),
        ;

        @JsonValue
        private final String value;

        @JsonCreator
        public static Reason fromString(String value) {
            return Arrays.stream(values())
                    .filter(reason -> reason.value.equals(value))
                    .findFirst()
                    .orElse(OTHER);
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OnlineScoringStatusList(List<OnlineScoringStatus> content) {
    }
}
