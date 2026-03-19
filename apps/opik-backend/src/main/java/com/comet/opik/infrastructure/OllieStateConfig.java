package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class OllieStateConfig {

    @JsonProperty
    @Positive private int maxUploadSizeBytes = 50 * 1024 * 1024; // 50MB
}
