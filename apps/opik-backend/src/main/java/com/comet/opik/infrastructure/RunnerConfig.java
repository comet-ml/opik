package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.Data;

@Data
public class RunnerConfig {

    @Valid @JsonProperty
    private String redisUrl = "redis://:opik@localhost:6379/0";
}
