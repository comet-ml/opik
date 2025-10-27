package com.comet.opik.domain;

import com.comet.opik.api.TimeInterval;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for generating time buckets based on time intervals.
 * Used by dashboard metrics to split time ranges into discrete buckets for aggregation.
 */
@Slf4j
@UtilityClass
public class TimeBucketGenerator {

    /**
     * Generate time buckets between start and end time based on the interval.
     * Buckets are aligned to natural boundaries (midnight for DAILY, start of hour for HOURLY, etc.)
     *
     * @param start    Start time (inclusive)
     * @param end      End time (inclusive)
     * @param interval Time interval (HOURLY, DAILY, WEEKLY)
     * @return List of time buckets covering the range
     */
    public static List<TimeBucket> generateBuckets(
            @NonNull Instant start,
            @NonNull Instant end,
            @NonNull TimeInterval interval) {

        if (start.isAfter(end)) {
            log.warn("Start time '{}' is after end time '{}'", start, end);
            return List.of();
        }

        // Align start to natural boundaries (midnight for DAILY, hour for HOURLY, etc.)
        Instant alignedStart = alignToInterval(start, interval);

        // Ensure we don't skip data by going back too far
        if (alignedStart.isAfter(start)) {
            alignedStart = alignedStart.minus(getStepDuration(interval));
        }

        Duration step = getStepDuration(interval);
        List<TimeBucket> buckets = new ArrayList<>();

        Instant bucketStart = alignedStart;
        while (bucketStart.isBefore(end)) {
            Instant bucketEnd = bucketStart.plus(step);

            // Only include buckets that overlap with the requested range
            if (bucketEnd.isAfter(start)) {
                buckets.add(TimeBucket.builder()
                        .start(bucketStart)
                        .end(bucketEnd)
                        .build());
            }

            bucketStart = bucketEnd;
        }

        log.debug("Generated '{}' time buckets for interval '{}' from '{}' to '{}' (aligned from '{}')",
                buckets.size(), interval, start, end, alignedStart);

        return buckets;
    }

    /**
     * Align an instant to natural interval boundaries.
     * - DAILY: Truncate to midnight (00:00:00 UTC)
     * - HOURLY: Truncate to the hour (XX:00:00 UTC)
     * - WEEKLY: Truncate to Monday midnight (00:00:00 UTC)
     */
    private static Instant alignToInterval(Instant instant, TimeInterval interval) {
        ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);

        return switch (interval) {
            case HOURLY -> zdt.truncatedTo(ChronoUnit.HOURS).toInstant();
            case DAILY -> zdt.truncatedTo(ChronoUnit.DAYS).toInstant();
            case WEEKLY -> zdt.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                    .truncatedTo(ChronoUnit.DAYS)
                    .toInstant();
        };
    }

    /**
     * Get the duration for each time bucket based on interval.
     */
    private static Duration getStepDuration(TimeInterval interval) {
        return switch (interval) {
            case HOURLY -> Duration.ofHours(1);
            case DAILY -> Duration.ofDays(1);
            case WEEKLY -> Duration.ofDays(7);
        };
    }

    /**
     * Calculate the total number of buckets that will be generated.
     * Useful for optimization decisions.
     */
    public static long calculateBucketCount(
            @NonNull Instant start,
            @NonNull Instant end,
            @NonNull TimeInterval interval) {

        if (start.isAfter(end)) {
            return 0;
        }

        Duration step = getStepDuration(interval);
        Duration totalDuration = Duration.between(start, end);

        return (totalDuration.toMillis() + step.toMillis() - 1) / step.toMillis();
    }
}
