package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AssistantSidebarConfig {

    @JsonProperty
    @NotBlank private String manifestUrl = "https://cdn.comet.com/ollie/v1/manifest.json";
}
