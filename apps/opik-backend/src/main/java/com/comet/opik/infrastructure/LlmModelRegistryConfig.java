package com.comet.opik.infrastructure;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class LlmModelRegistryConfig {

    private String defaultResource = "llm-models-default.yaml";

    private String localOverridePath;

    @Min(10) private int refreshIntervalSeconds = 300;
}
