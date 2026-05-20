package com.comet.opik.api.filter;

import com.comet.opik.domain.filter.FilterStrategy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum PromptVersionField implements Field {
    ID(ID_QUERY_PARAM, FieldType.STRING_STATE_DB),
    COMMIT(COMMIT_QUERY_PARAM, FieldType.STRING_STATE_DB),
    VERSION_NUMBER(VERSION_NUMBER_QUERY_PARAM, FieldType.NUMBER) {
        @Override
        public String normalizeValue(String value) {
            // version_number is displayed as "v1"/"v2" but stored as a number with a "v" prefix.
            // Accept either "3" or "v3"/"V3" from clients and strip the prefix so NUMBER
            // validation and the integer comparison in the WHERE clause both work.
            if (value != null && !value.isEmpty() && (value.charAt(0) == 'v' || value.charAt(0) == 'V')) {
                return value.substring(1);
            }
            return value;
        }
    },
    TEMPLATE(TEMPLATE_QUERY_PARAM, FieldType.STRING_STATE_DB),
    CHANGE_DESCRIPTION(CHANGE_DESCRIPTION_QUERY_PARAM, FieldType.STRING_STATE_DB),
    METADATA(METADATA_QUERY_PARAM, FieldType.DICTIONARY_STATE_DB) {
        @Override
        public boolean isDynamic(FilterStrategy filterStrategy) {
            return filterStrategy == FilterStrategy.PROMPT_VERSION;
        }
    },
    TYPE(TYPE_QUERY_PARAM, FieldType.ENUM),
    TAGS(TAGS_QUERY_PARAM, FieldType.STRING_STATE_DB),
    CREATED_AT(CREATED_AT_QUERY_PARAM, FieldType.DATE_TIME_STATE_DB),
    CREATED_BY(CREATED_BY_QUERY_PARAM, FieldType.STRING_STATE_DB),
    ;

    private final String queryParamField;
    private final FieldType type;
}
