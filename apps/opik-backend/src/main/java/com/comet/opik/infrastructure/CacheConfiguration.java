package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Data
public class CacheConfiguration {

    @Valid @JsonProperty
    private boolean enabled = false;

    @Valid @JsonProperty
    @NotNull private Duration defaultDuration;

    @Valid @JsonProperty
    private Map<String, Duration> caches;

    public Map<String, Duration> getCaches() {
        return Optional.ofNullable(caches).orElse(Map.of());
    }
}
