package com.comet.opik.api;

import com.comet.opik.domain.OpenTelemetryMapper;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;

/**
 * Mapper for converting Instant time boundaries to UUIDv7 bounds for efficient BETWEEN queries.
 *
 * UUIDv7 encodes the timestamp in the first 48 bits, allowing for lexicographic sorting by time.
 * This mapper creates predictable UUID boundaries (with zero random bits) to ensure correct BETWEEN
 * query semantics for time-based filtering.
 *
 * Why not use IdGenerator.getTimeOrderedEpoch()?
 * ================================================
 * While IdGenerator.getTimeOrderedEpoch() also creates UUIDs from timestamps using the reliable
 * java-uuid-generator library, it uses random bits for the lower 80 bits of the UUID. For time-range
 * queries, we need:
 * - Lower bound: UUID with ALL ZEROS for random bits (lexicographically smallest UUID at this timestamp)
 * - Upper bound: UUID at (timestamp+1ms) with ALL ZEROS (first UUID AFTER the end time)
 *
 * This ensures that BETWEEN queries correctly include all traces within the specified time range.
 * IdGenerator's random bits would make the bounds non-deterministic, breaking BETWEEN semantics.
 *
 * Implementation Note:
 * We use OpenTelemetryMapper.convertOtelIdToUUIDv7(new byte[8], ...) which:
 * 1. Takes a zero-filled byte array (produces all-zero random bits via SHA-256 hash)
 * 2. Encodes the timestamp in bytes 0-5
 * 3. Produces deterministic, predictable UUIDs suitable for range queries
 */
@UtilityClass
@Slf4j
public class InstantToUUIDMapper {

    /**
     * Generates a UUIDv7 lower bound from a timestamp for BETWEEN queries.
     * Creates the lexicographically smallest UUID with this timestamp (all zeros for random bits).
     *
     * @param timestamp the instant in time
     * @return the lower bound UUIDv7 (min UUID at this timestamp)
     */
    public static UUID toLowerBound(Instant timestamp) {
        if (timestamp == null) {
            return null;
        }

        return OpenTelemetryMapper.convertOtelIdToUUIDv7(new byte[8], timestamp.toEpochMilli());
    }

    /**
     * Generates a UUIDv7 upper bound from a timestamp for BETWEEN queries.
     * Creates the first UUID AFTER the specified time (by adding 1ms and using zero random bits).
     *
     * This ensures BETWEEN includes all UUIDs created within the end timestamp millisecond.
     * For example, if querying traces between 10:00:00.000 and 10:00:01.000:
     * - toLowerBound(10:00:00.000) gives the min UUID at 10:00:00
     * - toUpperBound(10:00:01.000) gives the min UUID at 10:00:02 (next millisecond)
     * - BETWEEN x AND y includes all UUIDs from 10:00:00 up to but NOT including 10:00:02
     * - Which correctly includes all UUIDs from 10:00:00 through 10:00:01.999

     *
     * @param timestamp the instant in time
     * @return the upper bound UUIDv7 (min UUID at timestamp+1ms)
     */
    public static UUID toUpperBound(Instant timestamp) {
        if (timestamp == null) {
            return null;
        }

        // Add 1ms and use zero random bytes to get the first UUID AFTER the end time
        // BETWEEN will include all UUIDs from toLowerBound to (but not including) this value
        long nextMillis = timestamp.toEpochMilli() + 1;
        return OpenTelemetryMapper.convertOtelIdToUUIDv7(new byte[8], nextMillis);
    }
}
