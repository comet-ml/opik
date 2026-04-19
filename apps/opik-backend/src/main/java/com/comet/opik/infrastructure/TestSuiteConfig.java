package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class TestSuiteConfig {

    @Valid @Min(1) @JsonProperty
    private int defaultRunsPerItem = 1;

    @Valid @Min(1) @JsonProperty
    private int fetchTimeoutSeconds = 10;
}
