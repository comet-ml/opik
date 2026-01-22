package com.comet.opik.domain.workspaces;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExperimentScopeMetadata(
        long experimentItemsCount,
        long limitCount) {

    public boolean canUseDynamicSorting() {
        return limitCount < 0 || experimentItemsCount <= limitCount;
    }

    public boolean cannotUseDynamicSorting() {
        return !canUseDynamicSorting();
    }
}
