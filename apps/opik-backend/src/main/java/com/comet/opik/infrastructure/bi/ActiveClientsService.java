package com.comet.opik.infrastructure.bi;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RHyperLogLogReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Estimates how many distinct clients have used this installation, from the anonymous identifiers the SDKs and the
 * frontend send. One all-time HyperLogLog per client type, so only approximate counts are kept, never the IDs.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ActiveClientsService {

    public enum ClientType {
        SDK,
        UI
    }

    private static final String KEY_FORMAT = "opik:active_clients:%s";
    private static final String TOTAL_SDK_CLIENTS = "total_sdk_clients";
    private static final String TOTAL_UI_CLIENTS = "total_ui_clients";

    private final @NonNull RedissonReactiveClient redisClient;

    // An SDK sends thousands of requests a day; only the first per client needs to reach Redis. Bounded, so unique
    // junk IDs cannot grow memory, and expiring, so a client is re-added daily should the Redis key ever be lost.
    // An evicted or expired entry only costs a repeated, idempotent PFADD.
    private final Cache<String, Boolean> recorded = CacheBuilder.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(1, TimeUnit.DAYS)
            .build();

    /** Fire-and-forget: never blocks or fails the request that carried the identifier. */
    public void record(@NonNull ClientType type, @NonNull String clientId) {
        var cacheKey = type + ":" + clientId;
        if (recorded.asMap().putIfAbsent(cacheKey, Boolean.TRUE) != null) {
            return;
        }

        hyperLogLog(type).add(clientId)
                .subscribe(
                        __ -> {
                        },
                        error -> {
                            recorded.invalidate(cacheKey);
                            log.warn("Failed to record active client of type '{}'", type, error);
                        });
    }

    /** Distinct clients per type since counting began, as {@code total_sdk_clients} and {@code total_ui_clients}. */
    public Mono<Map<String, Long>> getCounts() {
        return Mono.zip(hyperLogLog(ClientType.SDK).count(), hyperLogLog(ClientType.UI).count())
                .map(counts -> Map.of(TOTAL_SDK_CLIENTS, counts.getT1(), TOTAL_UI_CLIENTS, counts.getT2()));
    }

    private RHyperLogLogReactive<String> hyperLogLog(ClientType type) {
        return redisClient.getHyperLogLog(KEY_FORMAT.formatted(type.name().toLowerCase()), StringCodec.INSTANCE);
    }
}
