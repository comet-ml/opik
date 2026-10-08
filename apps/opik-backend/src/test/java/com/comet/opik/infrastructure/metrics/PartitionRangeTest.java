package com.comet.opik.infrastructure.metrics;

import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.LwdStat;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.PartitionStat;
import com.comet.opik.infrastructure.metrics.PartitionRange.Range;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.time.format.DateTimeFormatter.BASIC_ISO_DATE;
import static org.assertj.core.api.Assertions.assertThat;

class PartitionRangeTest {

    private final LocalDate today = LocalDate.now(ZoneOffset.UTC)
            .plusDays(RandomUtils.secure().randomInt(0, 3_650));
    private final LocalDate from = PartitionRange.floorFor(today.minusDays(RandomUtils.secure().randomInt(30, 3_650)));
    private final PartitionRange range = PartitionRange.of(from, today);

    // Boundary weeks: the first and last in range, and their out-of-range neighbours.
    private final LocalDate lastInRange = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1);
    private final String firstWeek = week(from);
    private final String lastWeek = week(lastInRange);
    private final String nextWeek = week(lastInRange.plusWeeks(1));
    private final String previousWeek = week(from.minusWeeks(1));

    private final String table = randomName("table");
    private final String otherTable = randomName("table");

    @Test
    void rangeClassifiesBoundariesAndPassesNonDayPartitionsThrough() {
        var partitions = List.of(firstWeek, lastWeek, nextWeek, "22991225", previousWeek, "19700105",
                "all", "202610", "20261399");

        var expected = Map.of(
                firstWeek, Range.IN_RANGE,
                lastWeek, Range.IN_RANGE,
                nextWeek, Range.OUT_OF_RANGE_FUTURE,
                "22991225", Range.OUT_OF_RANGE_FUTURE,
                previousWeek, Range.OUT_OF_RANGE_PAST,
                "19700105", Range.OUT_OF_RANGE_PAST,
                "all", Range.IN_RANGE,
                "202610", Range.IN_RANGE,
                "20261399", Range.IN_RANGE);

        assertThat(partitions.stream().collect(Collectors.toMap(Function.identity(), range::range)))
                .isEqualTo(expected);
    }

    @Test
    void groupFoldsOutOfRangePartitionsPerTableAndSide() {
        var inRange = randomStat(table, lastWeek);
        var future1 = randomStat(table, nextWeek);
        var future2 = randomStat(table, "22991225");
        var past1 = randomStat(table, previousWeek);
        var past2 = randomStat(table, "19700105");
        var otherFuture = randomStat(otherTable, nextWeek);
        var unpartitioned = randomStat(otherTable, "all");

        var expected = List.of(
                inRange,
                merged(future1, future2, Range.OUT_OF_RANGE_FUTURE),
                merged(past1, past2, Range.OUT_OF_RANGE_PAST),
                otherFuture.toBuilder().partition(Range.OUT_OF_RANGE_FUTURE.getValue()).build(),
                unpartitioned);

        var actual = range.group(List.of(inRange, future1, future2, past1, past2, otherFuture, unpartitioned));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void groupLwdFoldsWithTheSameLabelsAsGroup() {
        var inRange = randomLwd(table, firstWeek);
        var future1 = randomLwd(table, nextWeek);
        var future2 = randomLwd(table, "22991225");
        var past = randomLwd(table, previousWeek);
        var unpartitioned = randomLwd(otherTable, "all");

        var expected = List.of(
                inRange,
                future1.toBuilder()
                        .partition(Range.OUT_OF_RANGE_FUTURE.getValue())
                        .lwdRows(future1.lwdRows() + future2.lwdRows())
                        .build(),
                past.toBuilder().partition(Range.OUT_OF_RANGE_PAST.getValue()).build(),
                unpartitioned);

        var actual = range.groupLwd(List.of(inRange, future1, future2, past, unpartitioned));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void countByRangeIsExactAndReportsEveryRangePerTable() {
        var stats = Stream.of(firstWeek, lastWeek, nextWeek, "22991225", previousWeek)
                .map(partition -> randomStat(table, partition))
                .collect(Collectors.toList());
        stats.add(randomStat(otherTable, "all"));

        var expected = Map.of(
                table, Map.of(Range.IN_RANGE, 2L, Range.OUT_OF_RANGE_FUTURE, 2L, Range.OUT_OF_RANGE_PAST, 1L),
                otherTable, Map.of(Range.IN_RANGE, 1L, Range.OUT_OF_RANGE_FUTURE, 0L, Range.OUT_OF_RANGE_PAST, 0L));

        assertThat(range.countByRange(stats)).isEqualTo(expected);
    }

    private static PartitionStat merged(PartitionStat left, PartitionStat right, Range range) {
        return left.toBuilder()
                .partition(range.getValue())
                .parts(left.parts() + right.parts())
                .rows(left.rows() + right.rows())
                .bytes(left.bytes() + right.bytes())
                .maxPartBytes(Math.max(left.maxPartBytes(), right.maxPartBytes()))
                .lastActivityEpochSeconds(Math.max(left.lastActivityEpochSeconds(), right.lastActivityEpochSeconds()))
                .build();
    }

    private static PartitionStat randomStat(String table, String partition) {
        return PartitionStat.builder()
                .table(table)
                .partition(partition)
                .parts(randomLong())
                .rows(randomLong())
                .bytes(randomLong())
                .maxPartBytes(randomLong())
                .lastActivityEpochSeconds(randomLong())
                .build();
    }

    private static LwdStat randomLwd(String table, String partition) {
        return LwdStat.builder().table(table).partition(partition).lwdRows(randomLong()).build();
    }

    private static long randomLong() {
        return RandomUtils.secure().randomLong(1, 1_000_000_000L);
    }

    private static String week(LocalDate monday) {
        return monday.format(BASIC_ISO_DATE);
    }

    private static String randomName(String prefix) {
        return "%s_%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(16));
    }
}
