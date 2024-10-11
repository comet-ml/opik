package com.comet.opik.utils;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValidationUtilsTest {

    public static Stream<Arguments> testNullOrNotBlank() {
        return Stream.of(
                Arguments.of("", false),
                Arguments.of(" ", false),
                Arguments.of("\n", false),
                Arguments.of("a", true),
                Arguments.of(" a ", true),
                Arguments.of("\n a \n", true));
    }

    @ParameterizedTest
    @MethodSource
    void testNullOrNotBlank(String input, boolean expected) {
        assertEquals(expected, input.matches(ValidationUtils.NULL_OR_NOT_BLANK));
    }

}