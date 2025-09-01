package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PythonEvaluatorConfig {

    @JsonProperty
    @NotBlank private String url;

    @JsonProperty
    @Min(1) private int maxRetryAttempts = 4;

    @JsonProperty
    private Duration retryDelay = Duration.milliseconds(500);
}
