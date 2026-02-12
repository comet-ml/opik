package com.comet.opik.api;

import com.comet.opik.api.validation.DurationValidation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Duration;
import java.util.Map;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WorkspaceConfiguration(
        @DurationValidation @Schema(description = "Duration in ISO-8601 format (e.g., PT30M for 30 minutes, PT2H for 2 hours, P1D for 1 day). Minimum precision supported is seconds, please use a duration with seconds precision or higher. Also, the max duration allowed is 7 days.", implementation = String.class) Duration timeoutToMarkThreadAsInactive,
        @Schema(description = "Enable or disable data truncation in table views. When disabled, the frontend will limit pagination to prevent performance issues. Default: true (truncation enabled).", example = "true") Boolean truncationOnTables,
        @Schema(description = "Workspace-level color map. Maps label names to hex color values (e.g. #FF0000). Max 10000 entries.") @Size(max = 10000) Map<@Size(max = 256) String, @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Color must be a valid hex color (e.g. #FF0000)") String> colorMap) {
}
