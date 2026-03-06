package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class JobTimeoutConfig {

    @Valid @JsonProperty
    private int dailyUsageReportJobTimeout;

    @Valid @JsonProperty
    private int traceThreadsClosingJobTimeout;
}