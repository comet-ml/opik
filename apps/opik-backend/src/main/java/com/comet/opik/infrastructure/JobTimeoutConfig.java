package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.Data;

@Data
public class JobTimeoutConfig {

    @Valid @JsonProperty
    private int dailyUsageReportJobTimeout;

    @Valid @JsonProperty
    private int traceThreadsClosingJobTimeout;
}