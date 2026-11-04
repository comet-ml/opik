package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;

@Data
public class ClickHouseLogAppenderConfig {

    @JsonProperty
    @Min(1) @Max(100_000) private int batchSize;

    // The queue holds batchSize * maxQueuedBatches logs: that bounds its memory, and past it logs are dropped.
    @JsonProperty
    @Min(1) @Max(1_000) private int maxQueuedBatches;

    @Valid @JsonProperty
    @NotNull private Duration flushIntervalDuration;

    @JsonProperty
    @NotNull @DurationMin(millis = 1)
    private Duration shutdownTimeout;
}
