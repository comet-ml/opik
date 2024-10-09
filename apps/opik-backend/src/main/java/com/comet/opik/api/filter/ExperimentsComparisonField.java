package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum ExperimentsComparisonField implements Field {

    INPUT(INPUT_QUERY_PARAM, FieldType.STRING),
    EXPECTED_OUTPUT(EXPECTED_OUTPUT_QUERY_PARAM, FieldType.STRING),
    OUTPUT(OUTPUT_QUERY_PARAM, FieldType.STRING),
    METADATA(METADATA_QUERY_PARAM, FieldType.DICTIONARY),
    FEEDBACK_SCORES(FEEDBACK_SCORES_QUERY_PARAM, FieldType.FEEDBACK_SCORES_NUMBER),
    ;

    private final String queryParamField;
    private final FieldType type;
}
