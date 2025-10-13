package com.comet.opik.infrastructure.daytona;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DaytonaConfig {

    @NotBlank
    @JsonProperty
    private String url;

    @JsonProperty
    private String apiToken;

    @NotBlank
    @JsonProperty
    private String snapshotName;

    @NotNull
    @JsonProperty
    private Integer maxRetryAttempts;

    @NotNull
    @JsonProperty
    private Duration maxRetryDelay;

    @NotNull
    @JsonProperty
    private Duration minRetryDelay;

    @JsonProperty
    private String opikUrl;
}
