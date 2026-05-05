package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class EnvironmentConfig {

    @JsonProperty
    @Min(1) @Max(1000) private int maxPerWorkspace = 20;
}
