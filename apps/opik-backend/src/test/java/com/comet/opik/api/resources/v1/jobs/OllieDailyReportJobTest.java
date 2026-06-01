package com.comet.opik.api.resources.v1.jobs;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OllieDailyReportJobTest {

    static Stream<Arguments> windowCases() {
        return Stream.of(
                Arguments.of(LocalTime.of(10, 30), "10:20", "10:30"),
                Arguments.of(LocalTime.of(10, 35), "10:20", "10:30"),
                Arguments.of(LocalTime.of(10, 39), "10:20", "10:30"),
                Arguments.of(LocalTime.of(0, 0), "23:50", "24:00:00"),
                Arguments.of(LocalTime.of(0, 5), "23:50", "24:00:00"),
                Arguments.of(LocalTime.of(0, 10), "00:00", "00:10"),
                Arguments.of(LocalTime.of(23, 59), "23:40", "23:50"));
    }

    @ParameterizedTest
    @MethodSource("windowCases")
    void computeWindow_shouldReturnPreviousWindow(LocalTime now, String expectedStart, String expectedEnd) {
        var window = OllieDailyReportJob.computeWindow(now);

        assertThat(window.start()).isEqualTo(expectedStart);
        assertThat(window.end()).isEqualTo(expectedEnd);
    }

}
