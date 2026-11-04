package com.comet.opik.api.modelprice;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.Map;

@Builder(toBuilder = true)
public record CustomModelPriceResolveRequest(
        @NotBlank String model,
        String provider,
        @Schema(description = "A span's usage, with the keys an SDK sends; empty prices nothing") Map<String, Integer> usage) {
}
