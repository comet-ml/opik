package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OpikMetadataConfig {

    public record UsageReport(@Valid @JsonProperty boolean enabled, @Valid @JsonProperty String url) {
    }

    @Valid
    @JsonProperty
    @NotNull private String version;

    @Valid
    @NotNull @JsonProperty
    private UsageReport usageReport;
}
