package com.comet.opik.api;

import com.comet.opik.domain.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for InstantToUUIDMapper to validate UUIDv7 boundary generation
 * and ensure consistent timestamp encoding.
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
    void shouldGenerateLowerBound_withValidUUID() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");
        UUID expectedUUID = UUID.fromString("1926efec-0000-7000-0000-000000000000");
        when(idGenerator.getTimeOrderedEpoch(timestamp.toEpochMilli())).thenReturn(expectedUUID);

        // When
        UUID lowerBound = mapper.toLowerBound(timestamp);

        // Then
        assertThat(lowerBound).isEqualTo(expectedUUID);
    }

    @Test
    void shouldGenerateUpperBound_withIncrementedTime() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");
        UUID expectedUUID = UUID.fromString("1926efec-0000-7000-0000-000000000001");
        long nextMillis = timestamp.toEpochMilli() + 1;
        when(idGenerator.getTimeOrderedEpoch(nextMillis)).thenReturn(expectedUUID);

        // When
        UUID upperBound = mapper.toUpperBound(timestamp);

        // Then
        assertThat(upperBound).isEqualTo(expectedUUID);
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
    void shouldUseSameGeneratorForBothBounds() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");
        UUID lowerUUID = UUID.fromString("1926efec-0000-7000-0000-000000000000");
        UUID upperUUID = UUID.fromString("1926efec-0000-7000-0000-000000000001");

        when(idGenerator.getTimeOrderedEpoch(timestamp.toEpochMilli())).thenReturn(lowerUUID);
        when(idGenerator.getTimeOrderedEpoch(timestamp.toEpochMilli() + 1)).thenReturn(upperUUID);

        // When
        UUID lower = mapper.toLowerBound(timestamp);
        UUID upper = mapper.toUpperBound(timestamp);

        // Then
        assertThat(lower).isEqualTo(lowerUUID);
        assertThat(upper).isEqualTo(upperUUID);
    }

    @Test
    void shouldIncrementTimeByOneMillisecond_forUpperBound() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00.500Z");
        long expectedUpperMillis = timestamp.toEpochMilli() + 1;

        // When
        mapper.toUpperBound(timestamp);

        // Then - Verify the IdGenerator was called with the incremented time
        org.mockito.Mockito.verify(idGenerator).getTimeOrderedEpoch(expectedUpperMillis);
    }
}
