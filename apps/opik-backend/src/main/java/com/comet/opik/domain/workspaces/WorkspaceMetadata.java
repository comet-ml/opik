package com.comet.opik.domain.workspaces;

public record WorkspaceMetadata(
        double workspaceSizeGb,
        double totalTableSizeGb,
        double percentageOfTable) {

    public boolean canUseDynamicSorting() {
        return workspaceSizeGb <= 50;
    }

}
