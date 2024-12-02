package com.comet.opik.api;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record ProjectNameHolder(@NotBlank String project) {
}
