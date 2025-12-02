package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum OptimizationField implements Field {
    METADATA(METADATA_QUERY_PARAM, FieldType.DICTIONARY),
    DATASET_ID(DATASET_ID_QUERY_PARAM, FieldType.STRING),
    STATUS(STATUS_QUERY_PARAM, FieldType.ENUM),
    ;

    private final String queryParamField;
    private final FieldType type;
}
