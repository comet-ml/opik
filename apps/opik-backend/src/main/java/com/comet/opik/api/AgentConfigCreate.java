package com.comet.opik.api;

import com.comet.opik.domain.AgentBlueprint;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentConfigCreate(
        @Nullable UUID projectId,
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String projectName,
        @Nullable UUID id,
        @NotNull @Valid AgentBlueprint blueprint) {
}
