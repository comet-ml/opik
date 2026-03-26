package com.comet.opik.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = """
        Workspace version response.
        The opik_version field indicates which navigation mode the frontend should render:
        'version_1' (legacy workspace-scoped) or 'version_2' (project-first).""")
public record WorkspaceVersion(
        @Schema(description = """
                The determined Opik navigation version for this workspace.
                'version_1' = legacy workspace-scoped navigation,
                'version_2' = new project-first navigation.""") OpikVersion opikVersion) {
}
