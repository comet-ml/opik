package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.error.InvalidUUIDVersionException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

public interface IdGenerator {

    UUID generateId();

    UUID getTimeOrderedEpoch(long epochMilli);

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
