package com.comet.opik.api.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Set;

@RequiredArgsConstructor
@Getter
public enum Operator {
    CONTAINS("contains"),
    NOT_CONTAINS("not_contains"),
    STARTS_WITH("starts_with"),
    ENDS_WITH("ends_with"),
    EQUAL("="),
    NOT_EQUAL("!="),
    GREATER_THAN(">"),
    GREATER_THAN_EQUAL(">="),
    LESS_THAN("<"),
    LESS_THAN_EQUAL("<="),
    IS_EMPTY("is_empty"),
    IS_NOT_EMPTY("is_not_empty"),
    ;

    public static final Set<Operator> NO_VALUE_OPERATORS = Set.of(Operator.IS_EMPTY, Operator.IS_NOT_EMPTY);

    @JsonValue
    private final String queryParamOperator;

    @JsonCreator
    public static Operator fromString(String value) {
        return Arrays.stream(values())
                .filter(enumValue -> enumValue.queryParamOperator.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Operator '%s'".formatted(value)));
    }
}
