package com.comet.opik.infrastructure.auth;

import lombok.Builder;

@Builder(toBuilder = true)
public record WorkspaceInfo(String id, String name) {
}
