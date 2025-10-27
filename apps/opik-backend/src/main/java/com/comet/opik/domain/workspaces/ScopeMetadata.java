package com.comet.opik.domain.workspaces;

import lombok.Builder;

@Builder(toBuilder = true)
public record ScopeMetadata(
        double sizeGb,
        double totalTableSizeGb,
        double percentageOfTable,
        double limitSizeGb) {

    public boolean canUseDynamicSorting() {
        return limitSizeGb < 0 || sizeGb <= limitSizeGb;
    }

    public boolean cannotUseDynamicSorting() {
        return !canUseDynamicSorting();
    }
}
