package com.comet.opik.api.metrics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.Set;
import java.util.UUID;

/**
 * Request for the distinct span token-usage key names across projects. When {@code projectIds} is empty, the service
 * resolves it to every project in the workspace before querying; otherwise only the given projects are used. Mirrors
 * the per-project {@code GET /v1/private/projects/{id}/token-usage/names} endpoint, extended to a project set.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WorkspaceTokenUsageNamesRequest(Set<@NotNull UUID> projectIds) {
}
