package com.comet.opik.api;

import com.comet.opik.domain.OptimizerConfigEnv;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OptimizerConfigEnvUpdate(
        @NotNull UUID projectId,
        @NotNull @Size(min = 1, message = "envs must not be empty") @Valid List<OptimizerConfigEnv> envs) {
}
