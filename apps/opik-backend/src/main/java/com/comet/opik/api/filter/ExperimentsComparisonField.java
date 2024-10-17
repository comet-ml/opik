package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum ExperimentsComparisonField implements Field {

    INPUT(NEW_INPUT_QUERY_PARAM, FieldType.STRING),
    EXPECTED_OUTPUT(NEW_EXPECTED_OUTPUT_QUERY_PARAM, FieldType.STRING),
    OUTPUT(OUTPUT_QUERY_PARAM, FieldType.STRING),
    METADATA(NEW_METADATA_QUERY_PARAM, FieldType.DICTIONARY),
    CUSTOM_FIELD(NEW_CUSTOM_FIELD, FieldType.CUSTOM_FIELD),
    FEEDBACK_SCORES(FEEDBACK_SCORES_QUERY_PARAM, FieldType.FEEDBACK_SCORES_NUMBER),
    ;

    private final String queryParamField;
    private final FieldType type;
}
