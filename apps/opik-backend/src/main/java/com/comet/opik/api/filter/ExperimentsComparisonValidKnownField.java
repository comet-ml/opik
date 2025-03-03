package com.comet.opik.api.filter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

@RequiredArgsConstructor
@Getter
public enum ExperimentsComparisonValidKnownField implements Field {

    OUTPUT(OUTPUT_QUERY_PARAM, FieldType.STRING),
    FEEDBACK_SCORES(FEEDBACK_SCORES_QUERY_PARAM, FieldType.FEEDBACK_SCORES_NUMBER),
    ;

    private final String queryParamField;
    private final FieldType type;

    public static Optional<ExperimentsComparisonValidKnownField> from(String name) {
        return Arrays.stream(values())
                .filter(field -> field.queryParamField.equals(name))
                .findFirst();
    }
}
