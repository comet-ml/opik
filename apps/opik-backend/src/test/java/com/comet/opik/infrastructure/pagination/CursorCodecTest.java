package com.comet.opik.infrastructure.pagination;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for CursorCodec.
 *
 * These tests verify the encoding/decoding of cursors and error handling.
 *
 * @since 1.9.0
 */
class CursorCodecTest {

    @Test
    void testEncodeDecode_roundTrip() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Cursor original = new Cursor(timestamp, id);

        // When
        String encoded = CursorCodec.encode(original);
        Cursor decoded = CursorCodec.decode(encoded);

        // Then
        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.getTimestamp()).isEqualTo(timestamp);
        assertThat(decoded.getId()).isEqualTo(id);
    }

    @Test
    void testEncode_producesUrlSafeBase64() {
        // Given
        Cursor cursor = new Cursor(Instant.now(), UUID.randomUUID());

        // When
        String encoded = CursorCodec.encode(cursor);

        // Then
        assertThat(encoded).doesNotContain("+", "/", "=");
        assertThat(encoded).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void testEncode_consistentOutput() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Cursor cursor = new Cursor(timestamp, id);

        // When
        String encoded1 = CursorCodec.encode(cursor);
        String encoded2 = CursorCodec.encode(cursor);

        // Then
        assertThat(encoded1).isEqualTo(encoded2);
    }

    @Test
    void testDecode_invalidBase64_throwsException() {
        // Given
        String invalid = "not-valid-base64!!!";

        // When/Then
        assertThatThrownBy(() -> CursorCodec.decode(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed cursor");
    }

    @Test
    void testDecode_wrongSize_throwsException() {
        // Given - encode a different size
        String tooShort = "ABC";

        // When/Then
        assertThatThrownBy(() -> CursorCodec.decode(tooShort))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testIsValid_validCursor_returnsTrue() {
        // Given
        Cursor cursor = new Cursor(Instant.now(), UUID.randomUUID());
        String encoded = CursorCodec.encode(cursor);

        // When/Then
        assertThat(CursorCodec.isValid(encoded)).isTrue();
    }

    @Test
    void testIsValid_invalidCursor_returnsFalse() {
        // Given
        String invalid = "invalid-cursor";

        // When/Then
        assertThat(CursorCodec.isValid(invalid)).isFalse();
    }

    @Test
    void testIsValid_nullOrEmpty_returnsFalse() {
        assertThat(CursorCodec.isValid(null)).isFalse();
        assertThat(CursorCodec.isValid("")).isFalse();
    }

    @Test
    void testToDebugString_validCursor() {
        // Given
        Instant timestamp = Instant.parse("2025-01-15T10:30:00Z");
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Cursor cursor = new Cursor(timestamp, id);
        String encoded = CursorCodec.encode(cursor);

        // When
        String debug = CursorCodec.toDebugString(encoded);

        // Then
        assertThat(debug).contains("Cursor[");
        assertThat(debug).contains("timestamp=");
        assertThat(debug).contains("id=");
    }

    @Test
    void testToDebugString_invalidCursor() {
        // Given
        String invalid = "invalid-cursor";

        // When
        String debug = CursorCodec.toDebugString(invalid);

        // Then
        assertThat(debug).contains("InvalidCursor[");
        assertThat(debug).contains("error=");
    }

    @Test
    void testEncodedSize_is32Characters() {
        // Given - 24 bytes (8 + 16) encoded to Base64
        Cursor cursor = new Cursor(Instant.now(), UUID.randomUUID());

        // When
        String encoded = CursorCodec.encode(cursor);

        // Then
        // 24 bytes * 4/3 (Base64) = 32 characters
        assertThat(encoded).hasSize(32);
    }

    @Test
    void testDifferentTimestamps_produceDifferentCursors() {
        // Given
        UUID id = UUID.randomUUID();
        Cursor cursor1 = new Cursor(Instant.parse("2025-01-15T10:00:00Z"), id);
        Cursor cursor2 = new Cursor(Instant.parse("2025-01-15T11:00:00Z"), id);

        // When
        String encoded1 = CursorCodec.encode(cursor1);
        String encoded2 = CursorCodec.encode(cursor2);

        // Then
        assertThat(encoded1).isNotEqualTo(encoded2);
    }

    @Test
    void testDifferentIds_produceDifferentCursors() {
        // Given
        Instant timestamp = Instant.now();
        Cursor cursor1 = new Cursor(timestamp, UUID.randomUUID());
        Cursor cursor2 = new Cursor(timestamp, UUID.randomUUID());

        // When
        String encoded1 = CursorCodec.encode(cursor1);
        String encoded2 = CursorCodec.encode(cursor2);

        // Then
        assertThat(encoded1).isNotEqualTo(encoded2);
    }
}
