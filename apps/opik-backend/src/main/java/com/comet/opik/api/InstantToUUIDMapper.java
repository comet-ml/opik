package com.comet.opik.api;

import com.google.inject.Singleton;
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
 * We create UUIDs with all random bits set to 0 (lower bound) or 1 (upper bound) to guarantee
 * the minimum and maximum UUIDs for a given timestamp. This ensures that all UUIDs with timestamps
 * within the query range are included in the BETWEEN clause.
 */
@Singleton
@Slf4j
public class InstantToUUIDMapper {

    /**
     * Generates a UUIDv7 lower bound from a timestamp for BETWEEN queries.
     * Creates the lexicographically smallest UUID with this timestamp by setting all random bits to 0.
     *
     * UUIDv7 structure (RFC 9562):
     * - Bits 0-47: Unix timestamp in milliseconds (48 bits)
     * - Bits 48-51: Version = 0111 (7)
     * - Bits 52-63: Sub-millisecond precision or counter (12 bits) - set to 0 for lower bound
     * - Bits 64-65: Variant = 10
     * - Bits 66-127: Random (62 bits) - set to 0 for lower bound
     *
     * @param timestamp the instant in time
     * @return the lower bound UUIDv7 (min UUID at this timestamp)
     */
    public UUID toLowerBound(Instant timestamp) {
        if (timestamp == null) {
            return null;
        }

        long epochMilli = timestamp.toEpochMilli();

        // Most significant bits: [timestamp: 48 bits][version: 4 bits][random: 12 bits]
        // For lower bound, set the 12 random bits to 0
        long msb = (epochMilli << 16) // Shift timestamp to top 48 bits
                | (0x7000L); // Set version to 7 (bits 48-51), rest to 0

        // Least significant bits: [variant: 2 bits][random: 62 bits]
        // For lower bound, set all 62 random bits to 0
        long lsb = 0x8000000000000000L; // Set variant to 10 (bits 64-65), rest to 0

        return new UUID(msb, lsb);
    }

    /**
     * Generates a UUIDv7 upper bound from a timestamp for BETWEEN queries.
     * Creates the lexicographically largest UUID with this timestamp by setting all random bits to 1.
     *
     * This ensures BETWEEN includes all UUIDs created within the end timestamp millisecond.
     * For example, if querying traces between 10:00:00.000 and 10:00:01.000:
     * - toLowerBound(10:00:00.000) gives the minimum UUID at 10:00:00.000 (random bits = 0)
     * - toUpperBound(10:00:01.000) gives the maximum UUID at 10:00:01.000 (random bits = 1)
     * - BETWEEN x AND y includes all UUIDs from 10:00:00.000 through 10:00:01.000
     *
     * UUIDv7 structure (RFC 9562):
     * - Bits 0-47: Unix timestamp in milliseconds (48 bits)
     * - Bits 48-51: Version = 0111 (7)
     * - Bits 52-63: Sub-millisecond precision or counter (12 bits) - set to 1 for upper bound
     * - Bits 64-65: Variant = 10
     * - Bits 66-127: Random (62 bits) - set to 1 for upper bound
     *
     * @param timestamp the instant in time
     * @return the upper bound UUIDv7 (max UUID at this timestamp), or null if timestamp is null
     */
    public UUID toUpperBound(Instant timestamp) {
        if (timestamp == null) {
            return null;
        }

        long epochMilli = timestamp.toEpochMilli();

        // Most significant bits: [timestamp: 48 bits][version: 4 bits][random: 12 bits]
        // For upper bound, set the 12 random bits to 1
        long msb = (epochMilli << 16) // Shift timestamp to top 48 bits
                | (0x7FFFL); // Set version to 7 (bits 48-51), remaining 12 bits to 1

        // Least significant bits: [variant: 2 bits][random: 62 bits]
        // For upper bound, set all 62 random bits to 1
        // Start with variant 10 (0x8000000000000000L), then OR with max random bits (0x3FFFFFFFFFFFFFFFL)
        // 0x3FFFFFFFFFFFFFFFL has exactly 62 bits set to 1 (binary: 0011111111...1111)
        long lsb = 0x8000000000000000L | 0x3FFFFFFFFFFFFFFFL; // Variant 10 + all 62 random bits set to 1

        return new UUID(msb, lsb);
    }
}
