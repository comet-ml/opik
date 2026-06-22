package com.comet.opik.domain;

import com.comet.opik.api.error.InvalidUUIDException;
import com.comet.opik.api.error.InvalidUUIDException.Reason;
import com.comet.opik.infrastructure.db.UuidV7TimestampValidator;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@ImplementedBy(IdGeneratorImpl.class)
public interface IdGenerator {

    UUID generateId();

    UUID generateId(Instant timestamp);

    UUID getTimeOrderedEpoch(long epochMilli);

    /**
     * Validates an ingested {@code id}: it must be a version 7 UUID ({@link #validateVersion(UUID, String)})
     * whose embedded timestamp is within the configured ingestion window.
     * Rejects with HTTP 400. Encapsulates both data-quality checks behind one call.
     */
    void validateId(UUID id, String resource);

    Mono<UUID> validateIdAsync(UUID id, String resource);

    /**
     * Validates an ingested {@code id} on the update path: it must be a version 7 UUID
     * ({@link #validateVersion(UUID, String)}) and must not embed a timestamp far in the future (which
     * would corrupt the partition layout). Unlike {@link #validateId}, old ids are allowed, because
     * updating a long-lived entity (e.g. created months ago) is a legitimate operation.
     */
    Mono<UUID> validateIdForUpdateAsync(UUID id, String resource);

    static Mono<UUID> validateVersionAsync(@NonNull UUID id, String resource) {
        return Mono.fromCallable(() -> {
            validateVersion(id, resource);
            return id;
        });
    }

    static void validateVersion(@NonNull UUID id, String resource) {
        if (id.version() != 7)
            throw new InvalidUUIDException(Reason.NOT_V7, "%s id must be a version 7 UUID".formatted(resource));
    }
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class IdGeneratorImpl implements IdGenerator {

    private static final TimeBasedEpochGenerator UUID_GENERATOR = Generators.timeBasedEpochGenerator();

    private final @NonNull UuidV7TimestampValidator uuidV7TimestampValidator;

    @Override
    public UUID generateId() {
        return UUID_GENERATOR.generate();
    }

    @Override
    public UUID generateId(@NonNull Instant timestamp) {
        return getTimeOrderedEpoch(timestamp.toEpochMilli());
    }

    @Override
    public UUID getTimeOrderedEpoch(long epochMilli) {
        return UUID_GENERATOR.construct(epochMilli);
    }

    @Override
    public void validateId(@NonNull UUID id, String resource) {
        IdGenerator.validateVersion(id, resource);
        uuidV7TimestampValidator.validate(id);
    }

    @Override
    public Mono<UUID> validateIdAsync(@NonNull UUID id, String resource) {
        return Mono.fromCallable(() -> {
            validateId(id, resource);
            return id;
        });
    }

    private void validateIdForUpdate(UUID id, String resource) {
        IdGenerator.validateVersion(id, resource);
        uuidV7TimestampValidator.validateNotInFuture(id);
    }

    @Override
    public Mono<UUID> validateIdForUpdateAsync(@NonNull UUID id, String resource) {
        return Mono.fromCallable(() -> {
            validateIdForUpdate(id, resource);
            return id;
        });
    }
}
