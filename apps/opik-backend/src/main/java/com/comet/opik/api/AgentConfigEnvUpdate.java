package com.comet.opik.api;

import com.comet.opik.domain.AgentConfigEnv;
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
public record AgentConfigEnvUpdate(
        @NotNull UUID projectId,
        @NotNull @Size(min = 1, max = 100, message = "envs must contain between 1 and 100 items") @Valid List<@NotNull AgentConfigEnv> envs) {
}
