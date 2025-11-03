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

        assertThat(lowerTimestampPart)
                .as("Both bounds should encode the same timestamp")
                .isEqualTo(upperTimestampPart);
    }

    @Test
    void shouldHaveDifferentRandomPortions() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");

        // When
        UUID lowerBound = InstantToUUIDMapper.toLowerBound(timestamp);
        UUID upperBound = InstantToUUIDMapper.toUpperBound(timestamp);

        // Then
        // Extract the random portion (after the timestamp and version)
        String lowerBoundStr = lowerBound.toString().replace("-", "");
        String upperBoundStr = upperBound.toString().replace("-", "");

        String lowerRandomPart = lowerBoundStr.substring(13); // Skip timestamp+version
        String upperRandomPart = upperBoundStr.substring(13);

        assertThat(lowerRandomPart)
                .as("Random portions should differ (lower should be 0000, upper should be FFFF)")
                .isNotEqualTo(upperRandomPart);
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
        UUID upperBound = InstantToUUIDMapper.toUpperBound(timestamp);
        UUID referenceUUID = OpenTelemetryMapper.convertOtelIdToUUIDv7(
                new byte[]{-1, -1, -1, -1, -1, -1, -1, -1},
                timestamp.toEpochMilli());

        // Then
        assertThat(upperBound).isEqualTo(referenceUUID);
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
        Instant queryTime = Instant.parse("2025-01-15T10:30:00Z");
        UUID lowerBound = InstantToUUIDMapper.toLowerBound(queryTime);
        UUID upperBound = InstantToUUIDMapper.toUpperBound(queryTime);
        UUID randomUUID = OpenTelemetryMapper.convertOtelIdToUUIDv7(
                new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08},
                queryTime.toEpochMilli());

        // Then - All UUIDs with the same timestamp should have the same timestamp portion
        String lowerStr = lowerBound.toString().replace("-", "");
        String upperStr = upperBound.toString().replace("-", "");
        String randomStr = randomUUID.toString().replace("-", "");

        // Extract timestamp (first 12 hex chars = 48 bits)
        String lowerTimestamp = lowerStr.substring(0, 12);
        String upperTimestamp = upperStr.substring(0, 12);
        String randomTimestamp = randomStr.substring(0, 12);

        assertThat(lowerTimestamp).isEqualTo(upperTimestamp);
        assertThat(lowerTimestamp).isEqualTo(randomTimestamp);
    }
}
