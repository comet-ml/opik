package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class ResponseFormattingConfig {

    @Valid @JsonProperty
    @Positive private int truncationSize = 10001;

}