package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AttachmentsConfig {

    @NotNull @JsonProperty
    @Min(value = 1024, message = "stripMinSize must be at least 1KB") private Long stripMinSize;
}
