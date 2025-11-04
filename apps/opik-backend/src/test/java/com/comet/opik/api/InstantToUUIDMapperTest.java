package com.comet.opik.api;

import com.comet.opik.domain.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for InstantToUUIDMapper to validate UUIDv7 boundary generation
 * and ensure consistent timestamp encoding for time-based filtering.
 */
class InstantToUUIDMapperTest {

    @Mock
    private IdGenerator idGenerator;

    private InstantToUUIDMapper mapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mapper = new InstantToUUIDMapper(idGenerator);
    }

    @Test
    void shouldGenerateLowerBound_withTimestampEncoded() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");
        long expectedMillis = timestamp.toEpochMilli();
        UUID expectedUUID = UUID.fromString("1926efec-0000-7000-0000-000000000000");
        when(idGenerator.getTimeOrderedEpoch(expectedMillis)).thenReturn(expectedUUID);

        // When
        UUID lowerBound = mapper.toLowerBound(timestamp);

        // Then
        assertThat(lowerBound).isEqualTo(expectedUUID);
        verify(idGenerator).getTimeOrderedEpoch(expectedMillis);
    }

    @Test
    void shouldGenerateUpperBound_withNextMillisecond() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");
        long expectedMillis = timestamp.toEpochMilli() + 1;
        UUID expectedUUID = UUID.fromString("1926efec-0000-7000-0000-000000000001");
        when(idGenerator.getTimeOrderedEpoch(expectedMillis)).thenReturn(expectedUUID);

        // When
        UUID upperBound = mapper.toUpperBound(timestamp);

        // Then
        assertThat(upperBound).isEqualTo(expectedUUID);
        verify(idGenerator).getTimeOrderedEpoch(expectedMillis);
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

        UUID startLower = UUID.fromString("1926efec-0000-7000-0000-000000000000");
        UUID endUpper = UUID.fromString("1926efed-0000-7000-0000-000000000000");

        when(idGenerator.getTimeOrderedEpoch(startTime.toEpochMilli())).thenReturn(startLower);
        when(idGenerator.getTimeOrderedEpoch(endTime.toEpochMilli() + 1)).thenReturn(endUpper);

        // When
        UUID lower = mapper.toLowerBound(startTime);
        UUID upper = mapper.toUpperBound(endTime);

        // Then - Verify the bounds correctly represent the time range
        assertThat(lower).isEqualTo(startLower);
        assertThat(upper).isEqualTo(endUpper);

        // Verify IdGenerator was called with correct millisecond values
        verify(idGenerator).getTimeOrderedEpoch(startTime.toEpochMilli());
        verify(idGenerator).getTimeOrderedEpoch(endTime.toEpochMilli() + 1);
    }

    @Test
    void shouldAddOneMillisecondToUpperBoundTime() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00.500Z");
        long lowerMillis = timestamp.toEpochMilli();
        long upperMillis = lowerMillis + 1;

        // When
        mapper.toLowerBound(timestamp);
        mapper.toUpperBound(timestamp);

        // Then - Verify that upper bound is exactly 1ms after lower bound
        verify(idGenerator).getTimeOrderedEpoch(lowerMillis);
        verify(idGenerator).getTimeOrderedEpoch(upperMillis);
        assertThat(upperMillis).isEqualTo(lowerMillis + 1);
    }

    @Test
    void shouldHandleEpochTime() {
        // Given
        Instant epochTime = Instant.EPOCH;
        UUID expectedUUID = UUID.fromString("00000000-0000-7000-0000-000000000000");
        when(idGenerator.getTimeOrderedEpoch(0L)).thenReturn(expectedUUID);

        // When
        UUID lowerBound = mapper.toLowerBound(epochTime);

        // Then
        assertThat(lowerBound).isEqualTo(expectedUUID);
        verify(idGenerator).getTimeOrderedEpoch(0L);
    }

    @Test
    void shouldHandleLargeTimestamps() {
        // Given
        Instant futureTime = Instant.parse("2099-12-31T23:59:59.999Z");
        long expectedMillis = futureTime.toEpochMilli();
        UUID expectedUUID = UUID.fromString("ffffffff-ffff-7fff-ffff-ffffffffffff");
        when(idGenerator.getTimeOrderedEpoch(expectedMillis)).thenReturn(expectedUUID);

        // When
        UUID lowerBound = mapper.toLowerBound(futureTime);

        // Then
        assertThat(lowerBound).isEqualTo(expectedUUID);
        verify(idGenerator).getTimeOrderedEpoch(expectedMillis);
    }

    @Test
    void shouldUseSameGeneratorForConsistentResults() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");
        UUID firstCallUUID = UUID.fromString("1926efec-0000-7000-0000-000000000000");
        when(idGenerator.getTimeOrderedEpoch(timestamp.toEpochMilli())).thenReturn(firstCallUUID);

        // When - Call toLowerBound twice with same timestamp
        UUID result1 = mapper.toLowerBound(timestamp);
        UUID result2 = mapper.toLowerBound(timestamp);

        // Then - Should produce same UUID due to IdGenerator consistency
        assertThat(result1).isEqualTo(result2);
        assertThat(result1).isEqualTo(firstCallUUID);
    }

    @Test
    void shouldProduceDifferentUUIDsForDifferentTimestamps() {
        // Given
        Instant time1 = Instant.parse("2025-01-15T10:30:00.000Z");
        Instant time2 = Instant.parse("2025-01-15T10:30:01.000Z");

        UUID uuid1 = UUID.fromString("1926efec-0000-7000-0000-000000000000");
        UUID uuid2 = UUID.fromString("1926efed-0000-7000-0000-000000000000");

        when(idGenerator.getTimeOrderedEpoch(time1.toEpochMilli())).thenReturn(uuid1);
        when(idGenerator.getTimeOrderedEpoch(time2.toEpochMilli())).thenReturn(uuid2);

        // When
        UUID result1 = mapper.toLowerBound(time1);
        UUID result2 = mapper.toLowerBound(time2);

        // Then
        assertThat(result1).isNotEqualTo(result2);
        assertThat(result1).isEqualTo(uuid1);
        assertThat(result2).isEqualTo(uuid2);
    }
}
