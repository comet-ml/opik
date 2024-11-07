package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class BatchOperationsConfig {

    public record DatasetsConfig(@Valid @JsonProperty @Positive int maxExperimentInClauseSize) {
    }

    @Valid
    @JsonProperty
    @NotNull private DatasetsConfig datasets;

}
