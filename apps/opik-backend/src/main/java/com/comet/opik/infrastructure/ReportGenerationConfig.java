package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ReportGenerationConfig {

    @JsonProperty
    private String url = "";
}
