package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.concurrent.TimeUnit;

@Data
public class PythonEvaluatorConfig {

    @JsonProperty
    @NotBlank private String url;

    @JsonProperty
    @Min(1) private int maxRetryAttempts = 4;

    @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration maxRetryDelay = Duration.seconds(1);

    @JsonProperty
    @MaxDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration minRetryDelay = Duration.milliseconds(500);
}
