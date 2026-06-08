package com.comet.opik.api.resources.oauth;

import com.comet.opik.infrastructure.auth.WorkspaceInfo;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record AuthorizeContext(
        String clientName,
        String clientLogoUri,
        List<WorkspaceInfo> workspaces,
        String csrfToken) {
}
