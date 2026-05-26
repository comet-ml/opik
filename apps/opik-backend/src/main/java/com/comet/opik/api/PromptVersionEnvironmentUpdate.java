package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Set;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = """
        Replace the full set of environments assigned to a prompt version.
        The provided set becomes the new complete set of environments for this version.
        - Non-empty set: assigns each environment to this version; if another version of the same prompt currently owns any of those environments, ownership is moved atomically.
        - Empty set: clears all environments from this version.
        All environments must already exist in the workspace registry; unknown names return 404.
        """)
public record PromptVersionEnvironmentUpdate(
        @NotNull @Size(max = 100, message = "cannot exceed 100 environments") Set<@Pattern(regexp = Environment.NAME_PATTERN, message = Environment.NAME_PATTERN_MESSAGE) @Size(max = 150, message = "cannot exceed 150 characters") String> environments) {
}
