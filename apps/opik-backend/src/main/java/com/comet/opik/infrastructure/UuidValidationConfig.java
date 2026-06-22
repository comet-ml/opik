package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.concurrent.TimeUnit;

/**
 * UUIDv7 ingestion validation policy.
 *
 * <p>{@code enabled} is an operational kill-switch: when {@code false}, ids are not checked against
 * {@code window}. {@code window} bounds the embedded-timestamp distance from now, so a misbehaving
 * client can't land a row in a far-future partition.
 */
@Builder(toBuilder = true)
public record UuidValidationConfig(
        boolean enabled,
        @NotNull @MinDuration(value = 12, unit = TimeUnit.HOURS) @MaxDuration(value = 45, unit = TimeUnit.DAYS) Duration window) {
}
