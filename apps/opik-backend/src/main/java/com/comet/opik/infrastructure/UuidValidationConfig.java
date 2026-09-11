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
 *
 * <p>{@code auditOnly} adds a third, shadow state on top of the {@code enabled} switch. It only takes
 * effect when {@code enabled} is {@code true}: instead of rejecting out-of-window ids, the validator
 * records the reject-rate metric (tagged by workspace) and logs, but lets the write through. This
 * surfaces offending clients in real time without breaking ingestion. The effective mode is:
 * {@code enabled=false} → disabled (no-op); {@code enabled=true, auditOnly=true} → audit (count + log,
 * no reject); {@code enabled=true, auditOnly=false} → reject (HTTP 400).
 */
@Builder(toBuilder = true)
public record UuidValidationConfig(
        boolean enabled,
        boolean auditOnly,
        @NotNull @MinDuration(value = 12, unit = TimeUnit.HOURS) @MaxDuration(value = 45, unit = TimeUnit.DAYS) Duration window) {
}
