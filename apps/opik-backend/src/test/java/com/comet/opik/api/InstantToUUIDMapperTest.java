package com.comet.opik.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for InstantToUUIDMapper to validate UUIDv7 boundary generation
 * and ensure consistent timestamp encoding for time-based filtering.
 */
class InstantToUUIDMapperTest {

    private InstantToUUIDMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new InstantToUUIDMapper();
    }

    @Test
    void shouldGenerateLowerBound_withTimestampEncodedAndMinRandomBits() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");
        // Expected: timestamp bits + version 7 + variant 10 + all random bits = 0
        UUID expectedUUID = UUID.fromString("01946983-4c40-7000-8000-000000000000");

        // When
        UUID lowerBound = mapper.toLowerBound(timestamp);

        // Then
        assertThat(lowerBound).isEqualTo(expectedUUID);
        assertThat(lowerBound.version()).isEqualTo(7);
        assertThat(lowerBound.variant()).isEqualTo(2); // Variant 10 binary = 2 decimal
    }

    @Test
    void shouldGenerateUpperBound_withTimestampEncodedAndMaxRandomBits() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");
        // Expected: timestamp bits + version 7 + variant 10 + all random bits = 1
        UUID expectedUUID = UUID.fromString("01946983-4c40-7fff-bfff-ffffffffffff");

        // When
        UUID upperBound = mapper.toUpperBound(timestamp);

        // Then
        assertThat(upperBound).isEqualTo(expectedUUID);
        assertThat(upperBound.version()).isEqualTo(7);
        assertThat(upperBound.variant()).isEqualTo(2); // Variant 10 binary = 2 decimal
    }

    @Test
    void shouldReturnNull_whenLowerBoundTimestampIsNull() {
        // When
        UUID lowerBound = mapper.toLowerBound(null);

        // Then
        assertThat(lowerBound).isNull();
    }

    @Test
    void shouldReturnNull_whenUpperBoundTimestampIsNull() {
        // When
        UUID upperBound = mapper.toUpperBound(null);

        // Then
        assertThat(upperBound).isNull();
    }

    @Test
    void shouldMaintainTimestampBoundarySemantics() {
        // Given - Time range query semantics: BETWEEN lower AND upper
        Instant startTime = Instant.parse("2025-01-15T10:30:00.000Z");
        Instant endTime = Instant.parse("2025-01-15T10:30:01.000Z");

        // Lower bound of start time: all random bits = 0
        UUID expectedStartLower = UUID.fromString("01946983-4c40-7000-8000-000000000000");
        // Upper bound of end time: all random bits = 1
        UUID expectedEndUpper = UUID.fromString("01946983-5028-7fff-bfff-ffffffffffff");

        // When
        UUID lower = mapper.toLowerBound(startTime);
        UUID upper = mapper.toUpperBound(endTime);

        // Then - Verify the bounds correctly represent the time range
        assertThat(lower).isEqualTo(expectedStartLower);
        assertThat(upper).isEqualTo(expectedEndUpper);
        // Verify lower < upper lexicographically
        assertThat(lower.compareTo(upper)).isLessThan(0);
    }

    @Test
    void shouldUseSameTimestampForBothBounds() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00.500Z");

        // When
        UUID lower = mapper.toLowerBound(timestamp);
        UUID upper = mapper.toUpperBound(timestamp);

        // Then - Both should encode the same timestamp, only random bits differ
        // Extract timestamp from UUIDs (first 48 bits)
        long lowerTimestamp = lower.getMostSignificantBits() >>> 16;
        long upperTimestamp = upper.getMostSignificantBits() >>> 16;

        assertThat(lowerTimestamp).isEqualTo(upperTimestamp);
        assertThat(lowerTimestamp).isEqualTo(timestamp.toEpochMilli());
    }

    @Test
    void shouldHandleEpochTime() {
        // Given
        Instant epochTime = Instant.EPOCH;
        // Expected: timestamp = 0 + version 7 + variant 10 + all random bits = 0
        UUID expectedUUID = UUID.fromString("00000000-0000-7000-8000-000000000000");

        // When
        UUID lowerBound = mapper.toLowerBound(epochTime);

        // Then
        assertThat(lowerBound).isEqualTo(expectedUUID);
    }

    @Test
    void shouldHandleLargeTimestamps() {
        // Given
        Instant futureTime = Instant.parse("2099-12-31T23:59:59.999Z");
        long epochMilli = futureTime.toEpochMilli();

        // When
        UUID lowerBound = mapper.toLowerBound(futureTime);

        // Then - Verify timestamp is correctly encoded
        long extractedTimestamp = lowerBound.getMostSignificantBits() >>> 16;
        assertThat(extractedTimestamp).isEqualTo(epochMilli);
        assertThat(lowerBound.version()).isEqualTo(7);
    }

    @Test
    void shouldUseSameGeneratorForConsistentResults() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");

        // When - Call toLowerBound twice with same timestamp
        UUID result1 = mapper.toLowerBound(timestamp);
        UUID result2 = mapper.toLowerBound(timestamp);

        // Then - Should produce same UUID due to deterministic construction
        assertThat(result1).isEqualTo(result2);
    }

    @Test
    void shouldProduceDifferentUUIDsForDifferentTimestamps() {
        // Given
        Instant time1 = Instant.parse("2025-01-15T10:30:00.000Z");
        Instant time2 = Instant.parse("2025-01-15T10:30:01.000Z");

        // When
        UUID result1 = mapper.toLowerBound(time1);
        UUID result2 = mapper.toLowerBound(time2);

        // Then
        assertThat(result1).isNotEqualTo(result2);
        // Verify result2 is lexicographically greater than result1
        assertThat(result2.compareTo(result1)).isGreaterThan(0);
    }

    @Test
    void shouldEnsureLowerBoundIsLessThanUpperBound() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");

        // When
        UUID lower = mapper.toLowerBound(timestamp);
        UUID upper = mapper.toUpperBound(timestamp);

        // Then - Lower bound should be lexicographically less than upper bound
        assertThat(lower.compareTo(upper)).isLessThan(0);
    }

    @Test
    void shouldCoverAllPossibleUUIDsWithinTimestamp() {
        // Given - A specific millisecond timestamp
        Instant timestamp = Instant.parse("2025-01-15T10:30:00.500Z");

        // When
        UUID lower = mapper.toLowerBound(timestamp);
        UUID upper = mapper.toUpperBound(timestamp);

        // Then - Any UUIDv7 generated at this timestamp should be between lower and upper
        // This ensures BETWEEN lower AND upper will capture all UUIDs for this timestamp

        // Extract the timestamp portion (first 48 bits)
        long lowerTimestampBits = lower.getMostSignificantBits() >>> 16;
        long upperTimestampBits = upper.getMostSignificantBits() >>> 16;

        // Both should have the same timestamp
        assertThat(lowerTimestampBits).isEqualTo(upperTimestampBits);
        assertThat(lowerTimestampBits).isEqualTo(timestamp.toEpochMilli());

        // Lower should have minimum random bits, upper should have maximum
        // This ensures any UUID with this timestamp falls between them
        assertThat(lower).isLessThan(upper);
    }
}
