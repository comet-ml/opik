package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class BatchOperationsConfig {

    @Data
    public static class DatasetsConfig {
        private @Valid @JsonProperty @Positive int maxExperimentInClauseSize;
        private @Valid @JsonProperty @Positive int csvBatchSize;
    }

    @Valid @JsonProperty
    @NotNull private DatasetsConfig datasets;

}
