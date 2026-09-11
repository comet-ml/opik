package com.comet.opik.utils;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    public static Stream<Arguments> validateDateRangeParameters() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return Stream.of(
                Arguments.of(null, null, false),
                Arguments.of(today.minusDays(1), today, false),
                Arguments.of(today, today, false),
                Arguments.of(today, today.plusDays(5), false),
                Arguments.of(today, today.minusDays(1), true), // from_date after to_date
                Arguments.of(today.plusDays(1), null, true), // future from_date with defaulted to_date
                Arguments.of(today.plusDays(1), today.plusDays(2), false)); // explicit future window is valid
    }

    @ParameterizedTest
    @MethodSource
    void validateDateRangeParameters(LocalDate fromDate, LocalDate toDate, boolean shouldThrow) {
        if (shouldThrow) {
            assertThatThrownBy(() -> ValidationUtils.validateDateRangeParameters(fromDate, toDate))
                    .isInstanceOf(BadRequestException.class);
        } else {
            assertThatCode(() -> ValidationUtils.validateDateRangeParameters(fromDate, toDate))
                    .doesNotThrowAnyException();
        }
    }

}