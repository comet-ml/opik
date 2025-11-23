package com.comet.opik.infrastructure.pagination;

import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Codec for encoding and decoding cursor objects to/from Base64 strings.
 *
 * Encoding format:
 * - 8 bytes: timestamp (long, epoch millis)
 * - 16 bytes: UUID (2 longs: most significant bits + least significant bits)
 * Total: 24 bytes → Base64 encoded (32 characters)
 *
 * This binary encoding is more efficient than string-based formats:
 * - Smaller size (32 chars vs 50+ for string format)
 * - Faster to encode/decode
 * - No need for URL encoding
 *
 * @since 1.9.0
 */
@Slf4j
@UtilityClass
public class CursorCodec {

    private static final int CURSOR_BYTE_SIZE = 24; // 8 bytes timestamp + 16 bytes UUID

    /**
     * Encode a cursor to a Base64 URL-safe string
     *
     * @param cursor the cursor to encode
     * @return Base64-encoded cursor string
     */
    public static String encode(@NonNull Cursor cursor) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(CURSOR_BYTE_SIZE);

            // Write timestamp as long (8 bytes)
            buffer.putLong(cursor.getTimestamp().toEpochMilli());

            // Write UUID as two longs (16 bytes)
            buffer.putLong(cursor.getId().getMostSignificantBits());
            buffer.putLong(cursor.getId().getLeastSignificantBits());

            // Encode to Base64 URL-safe format (no padding for cleaner URLs)
            byte[] encoded = Base64.getUrlEncoder().withoutPadding().encode(buffer.array());
            return new String(encoded);
        } catch (Exception e) {
            log.error("Failed to encode cursor: {}", cursor, e);
            throw new IllegalArgumentException("Invalid cursor data", e);
        }
    }

    /**
     * Decode a Base64 cursor string to a Cursor object
     *
     * @param encoded the Base64-encoded cursor string
     * @return decoded Cursor object
     * @throws IllegalArgumentException if the cursor is malformed or invalid
     */
    public static Cursor decode(@NonNull String encoded) {
        try {
            // Decode from Base64
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);

            // Validate size
            if (decoded.length != CURSOR_BYTE_SIZE) {
                throw new IllegalArgumentException(
                    String.format("Invalid cursor size: expected %d bytes, got %d",
                        CURSOR_BYTE_SIZE, decoded.length)
                );
            }

            ByteBuffer buffer = ByteBuffer.wrap(decoded);

            // Read timestamp (8 bytes)
            long timestampMillis = buffer.getLong();
            Instant timestamp = Instant.ofEpochMilli(timestampMillis);

            // Read UUID (16 bytes)
            long mostSigBits = buffer.getLong();
            long leastSigBits = buffer.getLong();
            UUID id = new UUID(mostSigBits, leastSigBits);

            return new Cursor(timestamp, id);

        } catch (IllegalArgumentException e) {
            // Re-throw validation errors as-is
            throw e;
        } catch (Exception e) {
            log.warn("Failed to decode cursor: {}", encoded, e);
            throw new IllegalArgumentException("Malformed cursor: " + e.getMessage(), e);
        }
    }

    /**
     * Validate a cursor string without fully decoding it
     * Useful for quick validation in API layer
     *
     * @param encoded the cursor string to validate
     * @return true if the cursor appears valid
     */
    public static boolean isValid(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return false;
        }

        try {
            decode(encoded);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get a human-readable representation of an encoded cursor
     * Useful for debugging and logging
     *
     * @param encoded the encoded cursor string
     * @return human-readable string
     */
    public static String toDebugString(String encoded) {
        try {
            Cursor cursor = decode(encoded);
            return String.format("Cursor[timestamp=%s, id=%s, encoded=%s]",
                cursor.getTimestamp(),
                cursor.getId(),
                encoded.substring(0, Math.min(16, encoded.length())) + "..."
            );
        } catch (Exception e) {
            return String.format("InvalidCursor[encoded=%s, error=%s]",
                encoded.substring(0, Math.min(16, encoded.length())) + "...",
                e.getMessage()
            );
        }
    }
}
