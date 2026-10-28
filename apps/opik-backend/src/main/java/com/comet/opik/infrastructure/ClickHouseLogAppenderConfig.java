package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Duration;

@Data
public class ClickHouseLogAppenderConfig {

    // The appender queues up to 10 batches, so this also bounds its memory.
    @JsonProperty
    @Min(1) @Max(100_000) private int batchSize = 1000;

    @Valid @JsonProperty
    @NotNull private Duration flushIntervalDuration;
}
