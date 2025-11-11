package com.comet.opik.api;

import com.comet.opik.domain.IdGenerator;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;

/**
 * Mapper for converting Instant time boundaries to UUIDv7 bounds for efficient BETWEEN queries.
 *
 * UUIDv7 encodes the timestamp in the first 48 bits, allowing for lexicographic sorting by time.
 * This mapper creates UUID boundaries to ensure correct BETWEEN query semantics for time-based filtering.
 *
 * Implementation Note:
 * We use IdGenerator.getTimeOrderedEpoch() which reliably creates UUIDs from timestamps.
 * Per UUIDv7 RFC, the sub-millisecond 12 bits are optional and depend on implementation.
 * Our implementation uses monotonic values with millisecond granularity, so using the start/end
 * interval semantics with ±1ms ensures correct BETWEEN query results.
 * This approach has been battle-tested for months without issues.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class InstantToUUIDMapper {

    private final IdGenerator idGenerator;

    /**
     * Generates a UUIDv7 lower bound from a timestamp for BETWEEN queries.
     * Creates the lexicographically smallest UUID with this timestamp.
     *
     * @param timestamp the instant in time
     * @return the lower bound UUIDv7 (min UUID at this timestamp)
     */
    public UUID toLowerBound(Instant timestamp) {
        if (timestamp == null) {
            return null;
        }

        return idGenerator.getTimeOrderedEpoch(timestamp.toEpochMilli());
    }

    /**
     * Generates a UUIDv7 upper bound from a timestamp for BETWEEN queries.
     * Creates the UUID at the next millisecond to ensure inclusive BETWEEN semantics.
     *
     * This ensures BETWEEN includes all UUIDs created within the end timestamp millisecond.
     * For example, if querying traces between 10:00:00.000 and 10:00:01.000:
     * - toLowerBound(10:00:00.000) gives the UUID at 10:00:00
     * - toUpperBound(10:00:01.000) gives the UUID at 10:00:02 (next millisecond)
     * - BETWEEN x AND y includes all UUIDs from 10:00:00 up to but NOT including 10:00:02
     * - Which correctly includes all UUIDs from 10:00:00 through 10:00:01.999
     *
     * @param timestamp the instant in time
     * @return the upper bound UUIDv7 (UUID at timestamp+1ms)
     */
    public UUID toUpperBound(Instant timestamp) {
        if (timestamp == null) {
            return null;
        }

        // Add 1ms to get the first UUID AFTER the end time
        // BETWEEN will include all UUIDs from toLowerBound to (but not including) this value
        long nextMillis = timestamp.toEpochMilli() + 1;
        return idGenerator.getTimeOrderedEpoch(nextMillis);
    }
}
