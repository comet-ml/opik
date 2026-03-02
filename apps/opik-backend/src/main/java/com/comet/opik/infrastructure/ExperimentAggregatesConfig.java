package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class ExperimentAggregatesConfig {

    @Valid @JsonProperty
    @Positive private int batchSize;
}
