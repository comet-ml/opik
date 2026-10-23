package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * Connection to the opik-inference service, the shared decision model behind the guardrails checks. Read at
 * startup: {@link OpikConfiguration#isOpikInferenceConfigured()} refuses to boot when the service is toggled on
 * without a URL and an API key.
 */
@Data
@NoArgsConstructor
public class OpikInferenceConfig {

    @JsonProperty
    private String url;

    @JsonProperty
    @ToString.Exclude
    private String apiKey;

    @JsonProperty
    @Min(1) private int maxRetryAttempts = 2;

    @JsonProperty
    @NotNull @MinDuration(value = 10, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 60, unit = TimeUnit.SECONDS)
    private Duration connectTimeout = Duration.seconds(2);

    @JsonProperty
    @NotNull @MinDuration(value = 10, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 60, unit = TimeUnit.SECONDS)
    private Duration readTimeout = Duration.seconds(5);

    /**
     * Sent as {@code deadline_ms}: how long the decision service may spend on one call. The realtime lane caps it
     * at 5 s. The API default of 150 ms is below the measured p99 of a seven-question call under load, so the
     * value here must be set explicitly.
     */
    @JsonProperty
    @NotNull @MinDuration(value = 10, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 5, unit = TimeUnit.SECONDS)
    private Duration deadline = Duration.seconds(1);

    @JsonIgnore
    public boolean isConfigured() {
        return StringUtils.isNotBlank(url) && StringUtils.isNotBlank(apiKey);
    }
}
