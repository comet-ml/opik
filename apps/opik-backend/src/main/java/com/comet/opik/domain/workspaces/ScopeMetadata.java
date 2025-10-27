package com.comet.opik.domain.workspaces;

import com.comet.opik.infrastructure.WorkspaceSettings;

public record ScopeMetadata(
        double sizeGb,
        double totalTableSizeGb,
        double percentageOfTable,
        WorkspaceSettings workspaceSettings,
        ScopeType scopeType) {

    public enum ScopeType {
        WORKSPACE,
        PROJECT
    }

    public boolean canUseDynamicSorting() {
        double threshold = switch (scopeType) {
            case WORKSPACE -> workspaceSettings.getMaxSizeToAllowSorting();
            case PROJECT -> workspaceSettings.getMaxProjectSizeToAllowSorting();
        };

        return threshold < 0 || sizeGb <= threshold;
    }

}
