package com.comet.opik.infrastructure;

import lombok.Builder;

@Builder(toBuilder = true)
public record WorkspaceSettings(
        double maxSizeToAllowSorting,
        double maxProjectSizeToAllowSorting) {
}
