package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum ExperimentsComparisonField implements Field {

    OUTPUT(OUTPUT_QUERY_PARAM, FieldType.STRING),
    FEEDBACK_SCORES(FEEDBACK_SCORES_QUERY_PARAM, FieldType.FEEDBACK_SCORES_NUMBER),
    ;

    private final String queryParamField;
    private final FieldType type;
}
