package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.util.Map;

@Data
public class RateLimitConfig {

    public record LimitConfig(
            @JsonProperty @NotBlank String headerName,
            @JsonProperty @NotBlank String userFacingBucketName,
            @JsonProperty @PositiveOrZero long limit,
            @JsonProperty @Positive long durationInSeconds,
            @JsonProperty String errorMessage) {
    }

    @Valid @JsonProperty
    private boolean enabled;

    @Valid @JsonProperty
    private LimitConfig generalLimit;

    @Valid @JsonProperty
    private LimitConfig workspaceLimit;

    @Valid @JsonProperty
    private Map<String, LimitConfig> customLimits;

}
