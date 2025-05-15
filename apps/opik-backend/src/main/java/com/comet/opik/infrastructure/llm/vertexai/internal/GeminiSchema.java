package com.comet.opik.infrastructure.llm.vertexai.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
class GeminiSchema {

    private GeminiType type;
    private String format;
    private String description;
    private Boolean nullable;
    @JsonProperty("enum")
    private List<String> enumeration;
    private String maxItems;
    private Map<String, GeminiSchema> properties;
    private List<String> required;
    private GeminiSchema items;

}
