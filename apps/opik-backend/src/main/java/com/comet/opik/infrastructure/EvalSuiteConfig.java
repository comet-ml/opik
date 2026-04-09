package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EvalSuiteConfig {

    @Valid @NotBlank @JsonProperty
    private String defaultModelName = "gpt-5-nano";

    @Valid @Min(1) @JsonProperty
    private int defaultRunsPerItem = 1;

    @Valid @Min(1) @JsonProperty
    private int fetchTimeoutSeconds = 10;
}
