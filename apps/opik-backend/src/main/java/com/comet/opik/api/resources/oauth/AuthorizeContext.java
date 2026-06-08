package com.comet.opik.api.resources.oauth;

import com.comet.opik.infrastructure.auth.WorkspaceInfo;

import java.util.List;

public record AuthorizeContext(
        String clientName,
        String clientLogoUri,
        List<WorkspaceInfo> workspaces,
        String csrfToken) {
}
