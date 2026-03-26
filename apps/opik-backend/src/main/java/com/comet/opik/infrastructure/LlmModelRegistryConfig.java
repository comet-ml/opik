package com.comet.opik.infrastructure;

import lombok.Data;

@Data
public class LlmModelRegistryConfig {

    private String defaultResource = "llm-models-default.yaml";

    private String localOverridePath;

    private boolean remoteEnabled = false;

    private String remoteUrl;
}
