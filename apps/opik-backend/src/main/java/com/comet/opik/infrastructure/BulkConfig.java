package com.comet.opik.infrastructure;


import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.Data;

@Data
public class BulkConfig {

    @Valid
    @JsonProperty
    private Integer size;

}
