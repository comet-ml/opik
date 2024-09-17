package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
public class RateLimitConfig {

    public record LimitConfig(@Valid @JsonProperty @PositiveOrZero long limit, @Valid @JsonProperty @Positive long durationInSeconds) {
    }

    @Valid
    @JsonProperty
    private boolean enabled;

    @Valid
    @JsonProperty
    private LimitConfig generalEvents;

}
