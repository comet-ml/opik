package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class DeploymentConfig {
    @Valid @NotEmpty @JsonProperty
    private String baseUrl;
}
