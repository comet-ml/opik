package com.comet.opik.api.resources.utils;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.util.UUID;

/**
 * Test utility for generating UUIDv7 instances with specific timestamps.
 * Used across test suites to ensure consistent UUID generation for time-based testing.
 */
@UtilityClass
public class UUIDTestUtils {

    private static final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    /**
     * Generates a UUIDv7 with the specified timestamp embedded in the first 48 bits.
     * This ensures that the generated UUID's timestamp matches the provided instant,
     * which is essential for time-based filtering and bucketing in tests.
     *
     * @param timestamp the instant in time to embed in the UUID
     * @return a UUIDv7 with the timestamp embedded in its first 48 bits
     */
    public static UUID generateUUIDForTimestamp(Instant timestamp) {
        return generator.construct(timestamp.toEpochMilli());
    }
}
