package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum DatasetField implements Field {
    TAGS(TAGS_QUERY_PARAM, FieldType.STRING_STATE_DB),
    ;

    private final String queryParamField;
    private final FieldType type;
}