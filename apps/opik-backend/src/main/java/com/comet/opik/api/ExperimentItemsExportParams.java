package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@JsonTypeName(ExperimentItemsExportParams.TYPE)
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExperimentItemsExportParams(@NotNull UUID datasetId,
        @NotNull List<UUID> experimentIds) implements ExportParams {

    public static final String TYPE = "EXPERIMENT_ITEMS";

    /**
     * Comparing the same experiments in a different order is the same export, so the ids are sorted on the way in.
     * That keeps {@link #canonicalHash()} stable without the caller having to care about ordering.
     */
    public ExperimentItemsExportParams {
        experimentIds = experimentIds == null ? List.of() : experimentIds.stream().sorted().toList();
    }

    @Override
    public String exportType() {
        return TYPE;
    }
}
