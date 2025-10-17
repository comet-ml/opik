package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.queues.RqQueueConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Data
public class QueuesConfig {

    @Valid @NotNull @JsonProperty
    private boolean enabled;

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration defaultJobTtl;

    @Valid @NotNull @JsonProperty
    private Map<String, RqQueueConfig> queues = Map.of();

    public Optional<RqQueueConfig> getQueue(String queueName) {
        return Optional.ofNullable(queues.get(queueName));
    }

}
