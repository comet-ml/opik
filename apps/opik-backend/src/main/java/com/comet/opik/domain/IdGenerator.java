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
     * Validates an {@code id} that may legitimately point at an entity created in the past: it must be a
     * version 7 UUID ({@link #validateVersion(UUID, String)}) and must not embed a timestamp far in the
     * future (which would corrupt the partition layout / retention id-range). Unlike {@link #validateId},
     * old ids are allowed.
     *
     * <p>Used both on the update path (updating a long-lived entity created months ago is legitimate) and
     * for referenced/foreign ids on ingest (e.g. a span's {@code traceId}: retention orders spans by the
     * {@code trace_id} range assuming it is a time-ordered UUIDv7, and late spans on old traces are common,
     * so old is fine but non-v7 or future-dated must be rejected).
     */
    void validateIdNotInFuture(UUID id, String resource);

    Mono<UUID> validateIdNotInFutureAsync(UUID id, String resource);

    /**
     * Null-safe variant of {@link #validateIdNotInFuture} for optional referenced ids (e.g. an optional
     * {@code projectId} that may be resolved by name instead). No-op when {@code id} is null.
     */
    void validateIdNotInFutureIfPresent(UUID id, String resource);

    Mono<UUID> validateIdNotInFutureIfPresentAsync(UUID id, String resource);

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

    @Override
    public void validateIdNotInFuture(@NonNull UUID id, String resource) {
        IdGenerator.validateVersion(id, resource);
        uuidV7TimestampValidator.validateNotInFuture(id);
    }

    @Override
    public Mono<UUID> validateIdNotInFutureAsync(@NonNull UUID id, String resource) {
        return Mono.fromCallable(() -> {
            validateIdNotInFuture(id, resource);
            return id;
        });
    }

    @Override
    public void validateIdNotInFutureIfPresent(UUID id, String resource) {
        if (id != null) {
            validateIdNotInFuture(id, resource);
        }
    }

    @Override
    public Mono<UUID> validateIdNotInFutureIfPresentAsync(UUID id, String resource) {
        return id == null ? Mono.empty() : validateIdNotInFutureAsync(id, resource);
    }
}
