package com.comet.opik.api.automations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.time.Instant;

/** The workspace's GitHub token for {@code github.check}. Written once, never read back. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GithubIntegration(
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) @NotBlank String token,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Boolean configured,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Instant lastUpdatedAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable String lastUpdatedBy) {
}
