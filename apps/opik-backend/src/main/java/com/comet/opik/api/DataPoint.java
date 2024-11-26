package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;

@Builder(toBuilder = true)
public record DataPoint<T extends Number>(
        @NotNull Instant time,
        @Nullable @JsonInclude(JsonInclude.Include.ALWAYS) T value) {
}
