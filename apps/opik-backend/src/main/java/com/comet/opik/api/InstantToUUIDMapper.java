package com.comet.opik.api;

import com.comet.opik.domain.OpenTelemetryMapper;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;

/**
 * Mapper for converting Instant time boundaries to UUIDv7 bounds for efficient BETWEEN queries.
 * UUIDv7 encodes the timestamp in the first 48 bits, allowing for lexicographic sorting by time.
 */
@UtilityClass
@Slf4j
public class InstantToUUIDMapper {

    /**
     * Generates a UUIDv7 lower bound from a timestamp for BETWEEN queries.
     * Uses 0x00 for random bits to get the lexicographically smallest UUID with this timestamp.
     *
     * @param timestamp the instant in time
     * @return the lower bound UUIDv7
     */
    public static UUID toLowerBound(Instant timestamp) {
        if (timestamp == null) {
            return null;
        }

        return OpenTelemetryMapper.convertOtelIdToUUIDv7(new byte[8], timestamp.toEpochMilli());
    }

    /**
     * Generates a UUIDv7 upper bound from a timestamp for BETWEEN queries.
     * Uses zero bytes for the NEXT millisecond to get the first UUID after the end time.
     * This ensures BETWEEN includes all UUIDs created within the end timestamp.
     *
     * @param timestamp the instant in time
     * @return the upper bound UUIDv7 (UUID for next millisecond with zero random bits)
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
