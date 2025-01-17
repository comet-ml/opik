package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Duration;

@Data
public class ClickHouseLogAppenderConfig {

    @Valid @JsonProperty
    private int batchSize = 1000;

    @Valid @JsonProperty
    @NotNull private Duration flushIntervalDuration;
}
