package com.comet.opik.api.resources.oauth;

import com.comet.opik.infrastructure.auth.WorkspaceInfo;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AuthorizeContext(
        String clientName,
        String clientLogoUri,
        List<WorkspaceInfo> workspaces,
        String csrfToken) {
}
