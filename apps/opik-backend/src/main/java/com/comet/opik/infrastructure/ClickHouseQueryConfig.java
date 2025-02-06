package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.Data;

@Data
public class ClickHouseQueryConfig {

    @Valid @JsonProperty
    private boolean useFinal = true;

}
