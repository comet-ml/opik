package com.comet.opik.utils;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class PaginationUtilsTest {

    private static final List<Integer> LIST = IntStream.range(0, 50).boxed().toList();

    @ParameterizedTest
    @MethodSource
    void testPagination(int page, int size, List<Integer> expected) {
        List<Integer> actual = PaginationUtils.paginate(page, size, LIST);

        assertThat(actual).isEqualTo(expected);
    }

    private static Stream<Arguments> testPagination() {
        return Stream.of(
                arguments(1, 10, IntStream.range(0, 10).boxed().toList()),
                arguments(2, 15, IntStream.range(15, 30).boxed().toList()),
                arguments(4, 15, IntStream.range(45, 50).boxed().toList()),
                arguments(5, 15, List.of()),
                arguments(8, 7, IntStream.range(49, 50).boxed().toList()),
                arguments(6, 10, List.of()),
                arguments(1, 60, named("full list", LIST)));
    }

    @ParameterizedTest
    @MethodSource
    void testPaginationNegative(int page, int size, String expectedMessage) {
        assertThatThrownBy(() -> PaginationUtils.paginate(page, size, LIST))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(expectedMessage);
    }

    private static Stream<Arguments> testPaginationNegative() {
        return Stream.of(
                arguments(-1, 10, PaginationUtils.ERR_PAGE_INVALID.formatted(-1)),
                arguments(0, 10, PaginationUtils.ERR_PAGE_INVALID.formatted(0)),
                arguments(1, 0, PaginationUtils.ERR_SIZE_INVALID.formatted(0)),
                arguments(1, -1, PaginationUtils.ERR_SIZE_INVALID.formatted(-1)));
    }
}
