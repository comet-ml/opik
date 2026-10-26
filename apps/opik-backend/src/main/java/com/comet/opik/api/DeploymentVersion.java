package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

/**
 * One version this install has run (table {@code deployment_version_history}), and the moment it first started
 * here. {@code firstSeenAt} is local to this deployment — it is when this install was upgraded onto the version,
 * not when the version was released upstream, which is what makes it meaningful for cloud, STSaaS and
 * self-hosted alike.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeploymentVersion(
        @Schema(description = "The Opik version", accessMode = Schema.AccessMode.READ_ONLY) String version,
        @Schema(description = "When this deployment first started on this version", accessMode = Schema.AccessMode.READ_ONLY) Instant firstSeenAt) {

    /**
     * The running version plus everything this install has run before it, newest first.
     */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeploymentVersionHistory(
            @Schema(description = "The version currently running", accessMode = Schema.AccessMode.READ_ONLY) String currentVersion,
            @Schema(description = "Versions this deployment has run, newest first", accessMode = Schema.AccessMode.READ_ONLY) List<DeploymentVersion> versions) {
    }
}
