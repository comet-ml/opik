package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class UIConfig {

    @JsonProperty
    @Min(1) private int defaultPageSize = 100;
}
