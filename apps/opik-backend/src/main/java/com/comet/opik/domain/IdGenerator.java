package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.error.InvalidUUIDVersionException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IdGenerator {

    UUID generateId();

    UUID getTimeOrderedEpoch(long epochMilli);

    default UUID generateId(Instant timestamp) {
        return getTimeOrderedEpoch(timestamp.toEpochMilli());
    }

    /**
     * Extracts the Unix epoch timestamp in milliseconds from a UUIDv7.
     *
     * @param uuid the UUIDv7 instance
     * @return the extracted timestamp as Instant
     */
    static Instant extractTimestampFromUUIDv7(UUID uuid) {
        // Get the 64 most significant bits.
        long msb = uuid.getMostSignificantBits();
        // The top 48 bits represent the timestamp.
        long timestampMillis = msb >>> 16;
        return Instant.ofEpochMilli(timestampMillis);
    }

    static Mono<UUID> validateVersionAsync(UUID id, String resource) {
        if (id.version() != 7) {
            return Mono.error(
                    new InvalidUUIDVersionException(
                            new ErrorMessage(List.of("%s id must be a version 7 UUID".formatted(resource)))));
        }

        return Mono.just(id);
    }

    static void validateVersion(UUID id, String resource) {
        if (id.version() != 7)
            throw new InvalidUUIDVersionException(
                    new ErrorMessage(List.of("%s id must be a version 7 UUID".formatted(resource))));
    }
}
