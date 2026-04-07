package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ExperimentExecutionConfig {

    @Valid @Min(1) @JsonProperty
    private int maxConcurrentItems = 5;

    @Valid @NotBlank @JsonProperty
    private String defaultProjectName = "playground";
}
