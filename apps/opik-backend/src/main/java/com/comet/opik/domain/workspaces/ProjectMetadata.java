package com.comet.opik.domain.workspaces;

import com.comet.opik.infrastructure.WorkspaceSettings;

public record ProjectMetadata(
        double projectSizeGb,
        double totalTableSizeGb,
        double percentageOfTable,
        WorkspaceSettings workspaceSettings) {

    public boolean canUseDynamicSorting() {
        return workspaceSettings.getMaxProjectSizeToAllowSorting() < 0
                || projectSizeGb <= workspaceSettings.getMaxProjectSizeToAllowSorting();
    }

}
