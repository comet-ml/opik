package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AssistantSidebarConfig {

    @JsonProperty
    private String manifestUrl = "https://cdn.comet.com/ollie/v1/manifest.json";
}
