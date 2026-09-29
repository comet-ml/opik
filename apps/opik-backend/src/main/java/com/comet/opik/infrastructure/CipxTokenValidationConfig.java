package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Delegated validation of CIPX device tokens. Disabled by default.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CipxTokenValidationConfig {

    @Valid @JsonProperty
    private boolean enabled;

    @Valid @JsonProperty
    private String url;
}
