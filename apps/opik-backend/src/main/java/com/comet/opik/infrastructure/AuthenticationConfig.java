package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

@Data
public class AuthenticationConfig {

    public record UrlConfig(@Valid @JsonProperty @NotNull String url) {
    }

    @Valid @JsonProperty
    private boolean enabled;

    @Valid @JsonProperty
    private int apiKeyResolutionCacheTTLInSec;

    @Valid @JsonProperty
    private UrlConfig reactService;

    @Valid @JsonProperty
    @NotNull private Duration healthCheckTimeout = Duration.seconds(1);

    public boolean isReactServiceConfigured() {
        return reactService != null && StringUtils.isNotBlank(reactService.url());
    }
}
