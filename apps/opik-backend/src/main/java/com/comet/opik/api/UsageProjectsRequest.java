package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UsageProjectsRequest(
        @NotNull @Size(min = 1, max = 1000) Set<@NotBlank String> workspaceIds,
        @Size(max = 100) Set<@NotNull UUID> projectIds,
        @Size(max = 150) String name,
        @Min(1) @Max(1000) Integer limit) {

    public static final int DEFAULT_LIMIT = 100;
}
