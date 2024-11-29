package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UsageReportConfig {

    public record ServerStatsConfig(boolean enabled) {
    }

    @Valid
    @JsonProperty
    private boolean enabled;

    @Valid
    @JsonProperty
    private String url;

    @Valid
    @NotNull @JsonProperty
    private UsageReportConfig.ServerStatsConfig serverStats;

}
