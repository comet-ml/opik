package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.Data;

@Data
public class AnalyticsConfig {

    @Valid @JsonProperty
    private boolean enabled;

    @Valid @JsonProperty
    private String environment;

}
