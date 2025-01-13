package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.ToString;

@Data
public class EncryptionConfig {

    @Valid @JsonProperty
    @NotNull @ToString.Exclude
    private String key;
}
