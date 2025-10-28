package com.comet.opik.domain.workspaces;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
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
