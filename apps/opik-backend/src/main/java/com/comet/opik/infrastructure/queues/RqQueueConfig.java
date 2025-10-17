package com.comet.opik.infrastructure.queues;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

/**
 * Configuration for RQ queue behavior.
 *
 * TTL settings are configured at the queue level, not per message.
 * This ensures consistent behavior for all jobs in a queue.
 */
@Data
@Builder
public class RqQueueConfig {

    @Valid @NotNull @JsonProperty
    private final Duration jobTTl;

}
