package com.comet.opik.infrastructure.metrics;

import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.LwdStat;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.PartitionStat;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.time.format.DateTimeFormatter.BASIC_ISO_DATE;

/**
 * Keeps the per-partition partition-health series bounded however many partitions a table has
 * (OPIK-8631). Weekly tables accumulate far-future and pre-floor partitions from UUIDv7 ids with
 * bogus timestamps: each one would cost a series on every gauge while saying nothing, and enough of
 * them push the gauges past the OTel cardinality limit, where the SDK silently folds the overflow
 * into one series and can drop legitimate partitions. Out-of-range partitions therefore report as one
 * series per table per side; in-range and non-date partitions ({@code all}, monthly ids) pass through.
 * The floor is derived from the install date (see {@link #floorFor}), so it needs no config.
 *
 * @param from earliest in-range partition date (inclusive)
 * @param to   latest in-range partition date (inclusive): next week's Monday
 */
@Slf4j
public record PartitionRange(@NonNull LocalDate from, @NonNull LocalDate to) {

    @Getter
    @RequiredArgsConstructor
    public enum Range {
        IN_RANGE("in_range"),
        OUT_OF_RANGE_FUTURE("out_of_range_future"),
        OUT_OF_RANGE_PAST("out_of_range_past");

        /** The metric label value; out-of-range ones are also the partition label their bucket reports under. */
        private final String value;
    }

    // Weekly tables partition by toYYYYMMDD of the week's Monday; monthly (YYYYMM) and 'all' don't match.
    private static final Pattern DAY_PARTITION = Pattern.compile("\\d{8}");

    /**
     * Floor for an estate installed on {@code installationDate}: no legitimate row predates the install.
     * One extra week absorbs client clocks running slightly behind.
     */
    public static LocalDate floorFor(@NonNull LocalDate installationDate) {
        return installationDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
    }

    /** In range: from {@code from} through the week after the one containing {@code today}. */
    public static PartitionRange of(@NonNull LocalDate from, @NonNull LocalDate today) {
        return new PartitionRange(from, today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1));
    }

    public Range range(@NonNull String partition) {
        if (!DAY_PARTITION.matcher(partition).matches()) {
            return Range.IN_RANGE;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(partition, BASIC_ISO_DATE);
        } catch (DateTimeParseException exception) {
            // Weekly partition ids are always valid dates, so this means an unexpected partition expression.
            log.warn("ClickHouse partition metrics: unparsable day partition '{}', reported as in range", partition,
                    exception);
            return Range.IN_RANGE;
        }
        if (date.isAfter(to)) {
            return Range.OUT_OF_RANGE_FUTURE;
        }
        return date.isBefore(from) ? Range.OUT_OF_RANGE_PAST : Range.IN_RANGE;
    }

    /** The partition label a partition reports under: itself when in range, else its side's bucket. */
    public String label(@NonNull String partition) {
        Range range = range(partition);
        return range == Range.IN_RANGE ? partition : range.getValue();
    }

    /** Folds out-of-range partitions per table: sums for counts and sizes, max for largest part and activity. */
    public List<PartitionStat> group(@NonNull List<PartitionStat> stats) {
        return groupBy(stats, PartitionStat::table, stat -> stat.toBuilder().partition(label(stat.partition())).build(),
                PartitionStat::partition, (left, right) -> left.toBuilder()
                        .parts(left.parts() + right.parts())
                        .rows(left.rows() + right.rows())
                        .bytes(left.bytes() + right.bytes())
                        .maxPartBytes(Math.max(left.maxPartBytes(), right.maxPartBytes()))
                        .lastActivityEpochSeconds(
                                Math.max(left.lastActivityEpochSeconds(), right.lastActivityEpochSeconds()))
                        .build());
    }

    /** Same folding as {@link #group}, so {@code lwd_rows} still joins {@code rows} on (table, partition). */
    public List<LwdStat> groupLwd(@NonNull List<LwdStat> stats) {
        return groupBy(stats, LwdStat::table, stat -> stat.toBuilder().partition(label(stat.partition())).build(),
                LwdStat::partition,
                (left, right) -> left.toBuilder().lwdRows(left.lwdRows() + right.lwdRows()).build());
    }

    /**
     * Exact distinct partition count per table and range. Every range is present for every table, at 0
     * when empty, so an alert on the out-of-range count's increase sees the series before it grows.
     */
    public Map<String, Map<Range, Long>> countByRange(@NonNull List<PartitionStat> stats) {
        Map<String, Map<Range, Long>> counts = new LinkedHashMap<>();
        stats.stream()
                .map(stat -> List.of(stat.table(), stat.partition()))
                .distinct()
                .forEach(key -> counts
                        .computeIfAbsent(key.get(0), table -> new EnumMap<>(Map.of(
                                Range.IN_RANGE, 0L, Range.OUT_OF_RANGE_FUTURE, 0L, Range.OUT_OF_RANGE_PAST, 0L)))
                        .merge(range(key.get(1)), 1L, Long::sum));
        return counts.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
    }

    private static <T> List<T> groupBy(List<T> stats, Function<T, String> table, Function<T, T> relabel,
            Function<T, String> partition, BinaryOperator<T> merge) {
        return List.copyOf(stats.stream()
                .map(relabel)
                .collect(Collectors.toMap(stat -> List.of(table.apply(stat), partition.apply(stat)),
                        Function.identity(), merge, LinkedHashMap::new))
                .values());
    }
}
