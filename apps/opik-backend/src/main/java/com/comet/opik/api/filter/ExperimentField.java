package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum ExperimentField implements Field {
    METADATA(METADATA_QUERY_PARAM, FieldType.DICTIONARY),
    DATASET_ID(DATASET_ID_QUERY_PARAM, FieldType.STRING),
    PROMPT_IDS(PROMPT_IDS_QUERY_PARAM, FieldType.LIST),
    TAGS(TAGS_QUERY_PARAM, FieldType.LIST),
    FEEDBACK_SCORES(FEEDBACK_SCORES_QUERY_PARAM, FieldType.FEEDBACK_SCORES_NUMBER),
    EXPERIMENT_SCORES(EXPERIMENT_SCORES_QUERY_PARAM, FieldType.FEEDBACK_SCORES_NUMBER),
    ;

    private final String queryParamField;
    private final FieldType type;
}
