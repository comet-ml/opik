package com.comet.opik.domain.workspaces;

import com.comet.opik.infrastructure.WorkspaceSettings;

public record WorkspaceMetadata(
        double workspaceSizeGb,
        double totalTableSizeGb,
        double percentageOfTable,
        WorkspaceSettings workspaceSettings) {

    public boolean canUseDynamicSorting() {
        return workspaceSettings.getMaxSizeToAllowSorting() < 0
                || workspaceSizeGb <= workspaceSettings.getMaxSizeToAllowSorting();
    }

}
