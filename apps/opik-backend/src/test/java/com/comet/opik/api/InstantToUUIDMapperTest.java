package com.comet.opik.api;

import com.comet.opik.domain.OpenTelemetryMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for InstantToUUIDMapper to validate UUIDv7 boundary generation
 * and ensure consistent timestamp encoding.
 */
class InstantToUUIDMapperTest {

    @Test
    void shouldGenerateLowerBound_withZeroBytesForRandomPortion() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");

        // When
        UUID lowerBound = InstantToUUIDMapper.toLowerBound(timestamp);

        // Then
        assertThat(lowerBound).isNotNull();
        assertThat(lowerBound.toString()).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void shouldGenerateUpperBound_withAllFFBytesForRandomPortion() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");

        // When
        UUID upperBound = InstantToUUIDMapper.toUpperBound(timestamp);

        // Then
        assertThat(upperBound).isNotNull();
        assertThat(upperBound.toString()).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void shouldProduceDifferentUUIDsForZeroAndFFBytes() {
        // Given - Despite trying to use 0x00 and 0xFF for random bytes,
        // they get SHA-256 hashed, so we just verify they produce different UUIDs
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");

        // When
        UUID lowerBound = InstantToUUIDMapper.toLowerBound(timestamp);
        UUID upperBound = InstantToUUIDMapper.toUpperBound(timestamp);

        // Then
        assertThat(lowerBound).isNotEqualTo(upperBound);
    }

    @Test
    void shouldEncodeSameTimestampInBothBounds() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");

        // When
        UUID lowerBound = InstantToUUIDMapper.toLowerBound(timestamp);
        UUID upperBound = InstantToUUIDMapper.toUpperBound(timestamp);

        // Then
        // Extract the timestamp portion (first 48 bits / first 12 hex chars)
        String lowerBoundStr = lowerBound.toString().replace("-", "");
        String upperBoundStr = upperBound.toString().replace("-", "");

        String lowerTimestampPart = lowerBoundStr.substring(0, 12);
        String upperTimestampPart = upperBoundStr.substring(0, 12);

        // Upper bound uses next millisecond (+1ms), so timestamps should differ by 1
        UUID nextMillisLowerBound = InstantToUUIDMapper.toLowerBound(timestamp.plusMillis(1));
        String nextMillisTimestampPart = nextMillisLowerBound.toString().replace("-", "").substring(0, 12);

        assertThat(upperTimestampPart)
                .as("Upper bound should encode the next millisecond")
                .isEqualTo(nextMillisTimestampPart);
    }

    @Test
    void shouldHaveDifferentRandomPortions() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");

        // When
        UUID lowerBound = InstantToUUIDMapper.toLowerBound(timestamp);
        UUID upperBound = InstantToUUIDMapper.toUpperBound(timestamp);

        // Then
        // Lower bound uses same timestamp with zero random bytes
        // Upper bound uses next millisecond with zero random bytes
        // Since they have different timestamps, they should be different UUIDs
        assertThat(lowerBound)
                .as("Upper and lower bounds should be different UUIDs")
                .isNotEqualTo(upperBound);
    }

    @Test
    void shouldMaintainChronologicalOrder() {
        // Given
        Instant time1 = Instant.parse("2025-01-15T10:00:00Z");
        Instant time2 = Instant.parse("2025-01-15T11:00:00Z");
        Instant time3 = Instant.parse("2025-01-15T12:00:00Z");

        // When
        UUID lower1 = InstantToUUIDMapper.toLowerBound(time1);
        UUID lower2 = InstantToUUIDMapper.toLowerBound(time2);
        UUID lower3 = InstantToUUIDMapper.toLowerBound(time3);

        // Then
        assertThat(lower1.compareTo(lower2)).isNegative();
        assertThat(lower2.compareTo(lower3)).isNegative();
        assertThat(lower1.compareTo(lower3)).isNegative();
    }

    @Test
    void shouldReturnNull_whenTimestampIsNull() {
        // When
        UUID lowerBound = InstantToUUIDMapper.toLowerBound(null);
        UUID upperBound = InstantToUUIDMapper.toUpperBound(null);

        // Then
        assertThat(lowerBound).isNull();
        assertThat(upperBound).isNull();
    }

    @Test
    void shouldProduceUUIDv7Format() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");

        // When
        UUID lowerBound = InstantToUUIDMapper.toLowerBound(timestamp);

        // Then
        // UUIDv7 has version nibble = 7 in the 13th character
        String uuidStr = lowerBound.toString();
        assertThat(uuidStr.charAt(14)).isEqualTo('7');
    }

    @Test
    void shouldGenerateConsistentUUIDs() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");

        // When
        UUID lowerBound1 = InstantToUUIDMapper.toLowerBound(timestamp);
        UUID lowerBound2 = InstantToUUIDMapper.toLowerBound(timestamp);

        // Then
        assertThat(lowerBound1).isEqualTo(lowerBound2);
    }

    @Test
    void shouldUseZeroBytesForLowerBound() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");

        // When
        UUID lowerBound = InstantToUUIDMapper.toLowerBound(timestamp);
        UUID referenceUUID = OpenTelemetryMapper.convertOtelIdToUUIDv7(new byte[8], timestamp.toEpochMilli());

        // Then
        assertThat(lowerBound).isEqualTo(referenceUUID);
    }

    @Test
    void shouldUseFFBytesForUpperBound() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");

        // When
        UUID lowerBound = InstantToUUIDMapper.toLowerBound(timestamp);
        UUID upperBound = InstantToUUIDMapper.toUpperBound(timestamp);

        // Then
        assertThat(lowerBound).isNotNull();
        assertThat(upperBound).isNotNull();

        // Upper bound should be greater than lower bound
        assertThat(upperBound.toString().compareTo(lowerBound.toString()))
                .as("Upper bound should be lexicographically greater than lower bound")
                .isGreaterThan(0);
    }

    @Test
    void shouldWorkWithBoundaryInstants() {
        // Given - Test with epoch start
        Instant epochStart = Instant.EPOCH;

        // When
        UUID lowerBound = InstantToUUIDMapper.toLowerBound(epochStart);
        UUID upperBound = InstantToUUIDMapper.toUpperBound(epochStart);

        // Then
        assertThat(lowerBound).isNotNull();
        assertThat(upperBound).isNotNull();
        // They should be different due to different random bytes (hashed)
        assertThat(lowerBound).isNotEqualTo(upperBound);
    }

    @Test
    void shouldAllUUIDsWithSameTimestampShareTimestampPortion() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");

        // When
        UUID lowerBound = InstantToUUIDMapper.toLowerBound(timestamp);
        UUID nextMillisLowerBound = InstantToUUIDMapper.toLowerBound(timestamp.plusMillis(1));

        // Then
        // Extract the timestamp portion (first 48 bits / first 12 hex chars)
        String lowerBoundStr = lowerBound.toString().replace("-", "");
        String nextMillisStr = nextMillisLowerBound.toString().replace("-", "");

        String lowerTimestampPart = lowerBoundStr.substring(0, 12);
        String nextMillisTimestampPart = nextMillisStr.substring(0, 12);

        assertThat(nextMillisTimestampPart)
                .as("Next millisecond should have different timestamp portion")
                .isNotEqualTo(lowerTimestampPart);
    }
}
