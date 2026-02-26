package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.Data;

@Data
public class RunnerConfig {

    @Valid @JsonProperty
    private boolean enabled = false;

    @Valid @JsonProperty
    private int jobTimeoutSeconds = 1800;

    @Valid @JsonProperty
    private int maxPendingJobsPerRunner = 100;

    @Valid @JsonProperty
    private int heartbeatTtlSeconds = 15;

    @Valid @JsonProperty
    private int nextJobPollTimeoutSeconds = 10;

    @Valid @JsonProperty
    private int deadRunnerPurgeHours = 24;

    @Valid @JsonProperty
    private int completedJobTtlDays = 7;
}
