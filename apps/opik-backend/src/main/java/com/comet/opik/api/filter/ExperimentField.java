package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum ExperimentField implements Field {
    METADATA(METADATA_QUERY_PARAM, FieldType.DICTIONARY),
    ;

    private final String queryParamField;
    private final FieldType type;
}
