package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

@Data
public class UsageReportConfig {

    @Valid @JsonProperty
    private boolean enabled;

    @Valid @JsonProperty
    private String url;

    @Valid @JsonProperty
    private String anonymousId;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 10, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 60, unit = TimeUnit.SECONDS)
    private Duration connectTimeout = Duration.seconds(5);

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 10, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 60, unit = TimeUnit.SECONDS)
    private Duration readTimeout = Duration.seconds(10);

}
