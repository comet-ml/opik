package com.comet.opik.api.filter;

import com.comet.opik.api.Source;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@RequiredArgsConstructor
@Getter
public enum FieldType {
    STRING("string"),
    STRING_EXACT("string_exact"),
    STRING_STATE_DB("string_state_db"),
    DATE_TIME("date_time"),
    DATE_TIME_STATE_DB("date_time_state_db"),
    NUMBER("number"),
    DURATION("duration"), // Duration is treated as a NUMBER internally
    FEEDBACK_SCORES_NUMBER("feedback_scores_number"),
    DICTIONARY("dictionary"),
    DICTIONARY_STATE_DB("dictionary_state_db"),
    MAP("map"),
    LIST("list"),
    ENUM("enum"),
    ENUM_LEGACY("enum_legacy") {
        @Override
        public String buildFilter(String template, String dbField, int i, String filterValue,
                String enumFallbackTemplate) {
            return Source.legacyFallbackDbValue(filterValue)
                    .map(fallback -> "(%s)".formatted(template.formatted(dbField, i, fallback)))
                    .orElseGet(() -> "(%s)".formatted(enumFallbackTemplate.formatted(dbField, i)));
        }
    },
    ERROR_CONTAINER("error_container"),
    CUSTOM("custom"),
    ;

    @JsonValue
    private final String queryParamType;

    /**
     * Formats the ClickHouse filter expression for this field type.
     * <p>
     * Types with legacy fallback behaviour (e.g. {@link #ENUM_LEGACY}) override this method to
     * include the additional OR/AND clause for rows that predate the field's introduction.
     * All other types apply the two-argument template directly.
     * </p>
     *
     * @param template             the operator template for this field type (may contain {@code %3$s} for legacy types)
     * @param dbField              the ClickHouse column name
     * @param i                    the filter index used as the bind-parameter suffix
     * @param filterValue          the raw filter value supplied by the caller
     * @param enumFallbackTemplate the two-argument {@link #ENUM} template for the same operator,
     *                             used when a legacy type has no fallback mapping for {@code filterValue}
     * @return the fully-formatted {@code (…)} filter clause
     */
    public String buildFilter(String template, String dbField, int i, String filterValue,
            String enumFallbackTemplate) {
        return "(%s)".formatted(template.formatted(dbField, i));
    }

    /** Returns the legacy DB value to OR/AND alongside the primary filter value, if one exists. */
    public Optional<String> legacyFallbackDbValue(String filterValue) {
        return Optional.empty();
    }
}
