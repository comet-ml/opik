package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** Tuning for the free-form analytics SQL path. Credentials for its ClickHouse accounts live in their own blocks. */
@Data
public class FreeFormSqlConfig {

    /**
     * Beyond this many distinct ids in one result, name enrichment resolves none and every row keeps its raw id,
     * rather than labelling some rows and not others. Production p99 is 2 datasets and 11 projects per workspace
     * against a worst case of 4,120, so the default guards against a pathological result set rather than limiting
     * anyone.
     */
    @JsonProperty
    @Min(1) private int maxNameLookupIds = 5_000;
}
