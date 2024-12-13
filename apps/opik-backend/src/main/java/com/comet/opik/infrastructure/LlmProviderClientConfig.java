package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.concurrent.TimeUnit;

@Data
public class LlmProviderClientConfig {

    public record OpenApiClientConfig(String url) {
    }

    @Min(1)
    private Integer maxAttempts;

    @Min(1)
    private int delayMillis = 500;

    @Positive private Double jitterScale;

    @Positive private Double backoffExp;

    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    private Duration callTimeout;

    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    private Duration connectTimeout;

    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    private Duration readTimeout;

    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    private Duration writeTimeout;

    @Valid
    private OpenApiClientConfig openApiClient;
}
