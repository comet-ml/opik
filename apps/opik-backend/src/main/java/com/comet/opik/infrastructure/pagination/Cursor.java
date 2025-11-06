package com.comet.opik.infrastructure.pagination;

import lombok.NonNull;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a cursor for cursor-based pagination.
 * A cursor encodes a position in the result set using a timestamp and ID.
 * This ensures consistent pagination even when new data is added.
 *
 * Cursor format: Base64(timestamp_millis + ":" + uuid)
 *
 * Benefits over offset-based pagination:
 * - O(1) performance regardless of page depth
 * - Consistent results when data changes
 * - No duplicate/missing records during pagination
 * - Works perfectly with real-time data streams
 *
 * @since 1.9.0
 */
@Value
public class Cursor {

    /**
     * Timestamp component of the cursor
     * Used as the primary sorting/filtering field
     */
    @NonNull
    Instant timestamp;

    /**
     * Unique identifier component of the cursor
     * Used as a tie-breaker when timestamps are identical
     */
    @NonNull
    UUID id;

    /**
     * Create a cursor from a trace/entity
     *
     * @param timestamp the timestamp (e.g., last_updated_at)
     * @param id the unique identifier
     */
    public Cursor(@NonNull Instant timestamp, @NonNull UUID id) {
        this.timestamp = timestamp;
        this.id = id;
    }

    /**
     * Encode this cursor to a Base64 string for use in API responses
     *
     * @return Base64-encoded cursor string
     */
    public String encode() {
        return CursorCodec.encode(this);
    }

    /**
     * Decode a cursor from a Base64 string
     *
     * @param encoded the encoded cursor string
     * @return decoded Cursor object
     * @throws IllegalArgumentException if the cursor is invalid
     */
    public static Cursor decode(@NonNull String encoded) {
        return CursorCodec.decode(encoded);
    }

    /**
     * Create a cursor from timestamp and ID
     * Convenience factory method
     */
    public static Cursor of(Instant timestamp, UUID id) {
        return new Cursor(timestamp, id);
    }

    /**
     * Get the timestamp as epoch milliseconds
     * Useful for database queries
     */
    public long getTimestampMillis() {
        return timestamp.toEpochMilli();
    }

    @Override
    public String toString() {
        return String.format("Cursor[timestamp=%s, id=%s]", timestamp, id);
    }
}
