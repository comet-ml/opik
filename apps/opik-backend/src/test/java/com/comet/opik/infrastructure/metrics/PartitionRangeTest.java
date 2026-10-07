package com.comet.opik.infrastructure.metrics;

import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.LwdStat;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.PartitionStat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.metrics.PartitionRange.IN_RANGE;
import static com.comet.opik.infrastructure.metrics.PartitionRange.OUT_OF_RANGE_FUTURE;
import static com.comet.opik.infrastructure.metrics.PartitionRange.OUT_OF_RANGE_PAST;
import static java.time.format.DateTimeFormatter.BASIC_ISO_DATE;
import static org.assertj.core.api.Assertions.assertThat;

class PartitionRangeTest {

    // Wednesday: the current week starts 2026-10-05, so the last in-range week is 2026-10-12.
    private static final PartitionRange RANGE = PartitionRange.of(
            LocalDate.parse("2024-01-01"), LocalDate.parse("2026-10-07"));

    @Test
    void ofEndsAtNextWeeksMonday() {
        assertThat(RANGE).isEqualTo(new PartitionRange(LocalDate.parse("2024-01-01"), LocalDate.parse("2026-10-12")));
    }

    @ParameterizedTest
    @CsvSource({
            "20240101, in_range",
            "20261005, in_range",
            "20261012, in_range",
            "20261019, out_of_range_future",
            "22990101, out_of_range_future",
            "20231225, out_of_range_past",
            "19000101, out_of_range_past",
            "all, in_range",
            "202610, in_range",
            "20261399, in_range"
    })
    void range(String partition, String expected) {
        assertThat(RANGE.range(partition)).isEqualTo(expected);
    }

    @Test
    void groupFoldsOutOfRangePartitionsPerTableAndSide() {
        var stats = List.of(
                stat("spans_local", "20261005", 2, 100, 1_000, 600, 50),
                stat("spans_local", "20261019", 1, 10, 100, 100, 70),
                stat("spans_local", "22990101", 3, 20, 300, 250, 60),
                stat("spans_local", "20231225", 1, 5, 50, 50, 10),
                stat("spans_local", "19700105", 2, 7, 70, 40, 20),
                stat("traces_local", "21000104", 1, 1, 10, 10, 5),
                stat("spans_pre_cutover_backup", "all", 4, 1_000, 9_000, 8_000, 40));

        var expected = List.of(
                stat("spans_local", "20261005", 2, 100, 1_000, 600, 50),
                stat("spans_local", OUT_OF_RANGE_FUTURE, 4, 30, 400, 250, 70),
                stat("spans_local", OUT_OF_RANGE_PAST, 3, 12, 120, 50, 20),
                stat("traces_local", OUT_OF_RANGE_FUTURE, 1, 1, 10, 10, 5),
                stat("spans_pre_cutover_backup", "all", 4, 1_000, 9_000, 8_000, 40));

        assertThat(RANGE.group(stats)).isEqualTo(expected);
    }

    @Test
    void groupLwdFoldsWithTheSameLabelsAsGroup() {
        var stats = List.of(
                lwd("spans_local", "20261005", 4),
                lwd("spans_local", "20261019", 1),
                lwd("spans_local", "22990101", 2),
                lwd("spans_local", "20231225", 3),
                lwd("traces", "all", 9));

        var expected = List.of(
                lwd("spans_local", "20261005", 4),
                lwd("spans_local", OUT_OF_RANGE_FUTURE, 3),
                lwd("spans_local", OUT_OF_RANGE_PAST, 3),
                lwd("traces", "all", 9));

        assertThat(RANGE.groupLwd(stats)).isEqualTo(expected);
    }

    @Test
    void countByRangeIsExactAndReportsEveryRangePerTable() {
        var stats = List.of(
                stat("spans_local", "20261005", 1, 1, 1, 1, 1),
                stat("spans_local", "20261012", 1, 1, 1, 1, 1),
                stat("spans_local", "20261019", 1, 1, 1, 1, 1),
                stat("spans_local", "22990101", 1, 1, 1, 1, 1),
                stat("spans_local", "20231225", 1, 1, 1, 1, 1),
                stat("traces", "all", 1, 1, 1, 1, 1));

        var expected = Map.of(
                "spans_local", Map.of(IN_RANGE, 2L, OUT_OF_RANGE_FUTURE, 2L, OUT_OF_RANGE_PAST, 1L),
                "traces", Map.of(IN_RANGE, 1L, OUT_OF_RANGE_FUTURE, 0L, OUT_OF_RANGE_PAST, 0L));

        assertThat(RANGE.countByRange(stats)).isEqualTo(expected);
    }

    @Test
    void seriesStayBoundedAsOutOfRangePartitionsGrow() {
        var inRange = stat("spans_local", "20261005", 1, 1, 1, 1, 1);
        var farFuture = IntStream.range(0, 15_000)
                .mapToObj(week -> LocalDate.parse("2030-01-07").plusWeeks(week))
                .map(date -> stat("spans_local", date.format(BASIC_ISO_DATE),
                        1, 1, 1, 1, 1));
        var stats = Stream.concat(Stream.of(inRange), farFuture).toList();

        var expected = List.of(inRange, stat("spans_local", OUT_OF_RANGE_FUTURE, 15_000, 15_000, 15_000, 1, 1));

        assertThat(RANGE.group(stats)).isEqualTo(expected);
        assertThat(RANGE.countByRange(stats)).isEqualTo(Map.of(
                "spans_local", Map.of(IN_RANGE, 1L, OUT_OF_RANGE_FUTURE, 15_000L, OUT_OF_RANGE_PAST, 0L)));
    }

    private static PartitionStat stat(String table, String partition, long parts, long rows, long bytes,
            long maxPartBytes, long lastActivity) {
        return PartitionStat.builder()
                .table(table)
                .partition(partition)
                .parts(parts)
                .rows(rows)
                .bytes(bytes)
                .maxPartBytes(maxPartBytes)
                .lastActivityEpochSeconds(lastActivity)
                .build();
    }

    private static LwdStat lwd(String table, String partition, long lwdRows) {
        return LwdStat.builder().table(table).partition(partition).lwdRows(lwdRows).build();
    }
}
