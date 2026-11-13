package com.comet.opik.domain.automations;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Which run created an object, keyed by the object's id, so the event that object later fires can name
 * its parent run and inherit its depth. Short-lived: a chain is a matter of minutes, and nothing is lost
 * if a key expires but the link.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AutomationOriginStore {

    static final Duration TTL = Duration.ofDays(2);
    private static final String KEY = "automation:origin:%s";

    private final @NonNull RedissonReactiveClient redisson;

    @Builder(toBuilder = true)
    public record Origin(@NonNull UUID runId, int depth) {
    }

    public Mono<Void> record(@NonNull String itemId, @NonNull Origin origin) {
        return redisson.<String>getBucket(KEY.formatted(itemId), StringCodec.INSTANCE)
                .set("%s:%d".formatted(origin.runId(), origin.depth()), TTL)
                .then();
    }

    public Mono<Optional<Origin>> find(@NonNull String itemId) {
        return redisson.<String>getBucket(KEY.formatted(itemId), StringCodec.INSTANCE).get()
                .map(value -> {
                    int colon = value.lastIndexOf(':');
                    return Optional.of(new Origin(UUID.fromString(value.substring(0, colon)),
                            Integer.parseInt(value.substring(colon + 1))));
                })
                .defaultIfEmpty(Optional.empty());
    }
}
