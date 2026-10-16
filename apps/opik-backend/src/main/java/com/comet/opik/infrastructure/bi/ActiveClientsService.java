package com.comet.opik.infrastructure.bi;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RHyperLogLogReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Estimates how many distinct clients use this installation, from the anonymous identifiers the SDKs and the
 * frontend send. One HyperLogLog per client type and UTC day, so only approximate counts are kept, never the IDs.
 */
@Slf4j
@Singleton
public class ActiveClientsService {

    public enum ClientType {
        SDK,
        UI
    }

    private static final String KEY_FORMAT = "opik:active_clients:%s:%s";

    // Longer than the widest reporting window, so a monthly count always finds all its days.
    private static final Duration KEY_TTL = Duration.ofDays(35);
    private static final int WEEK_DAYS = 7;
    private static final int MONTH_DAYS = 30;

    private final RedissonReactiveClient redisClient;

    // An SDK sends thousands of requests a day; only the first per client and day needs to reach Redis.
    // Bounded, so unique junk IDs cannot grow memory; an evicted entry only costs a repeated, idempotent PFADD.
    private final Cache<String, Boolean> recorded = CacheBuilder.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(1, TimeUnit.DAYS)
            .build();

    @Inject
    public ActiveClientsService(@NonNull RedissonReactiveClient redisClient) {
        this.redisClient = redisClient;
    }

    /** Fire-and-forget: never blocks or fails the request that carried the identifier. */
    public void record(@NonNull ClientType type, @NonNull String clientId) {
        var key = key(type, today());
        var cacheKey = key + ":" + clientId;
        if (recorded.asMap().putIfAbsent(cacheKey, Boolean.TRUE) != null) {
            return;
        }

        var hll = hyperLogLog(key);
        hll.add(clientId)
                .then(hll.expire(KEY_TTL))
                .subscribe(
                        __ -> {
                        },
                        error -> {
                            recorded.invalidate(cacheKey);
                            log.warn("Failed to record active client", error);
                        });
    }

    /**
     * Distinct clients per type over the day, week and month ending yesterday (UTC), matching the window of the
     * other daily usage report counts. Keys are named {@code daily_<type>_clients}, {@code weekly_…} and
     * {@code monthly_…}.
     */
    public Mono<Map<String, Long>> getCounts() {
        var yesterday = today().minusDays(1);
        return Mono.zip(
                count(ClientType.SDK, yesterday, 1),
                count(ClientType.SDK, yesterday, WEEK_DAYS),
                count(ClientType.SDK, yesterday, MONTH_DAYS),
                count(ClientType.UI, yesterday, 1),
                count(ClientType.UI, yesterday, WEEK_DAYS),
                count(ClientType.UI, yesterday, MONTH_DAYS))
                .map(counts -> Map.of(
                        "daily_sdk_clients", counts.getT1(),
                        "weekly_sdk_clients", counts.getT2(),
                        "monthly_sdk_clients", counts.getT3(),
                        "daily_ui_clients", counts.getT4(),
                        "weekly_ui_clients", counts.getT5(),
                        "monthly_ui_clients", counts.getT6()));
    }

    /** PFCOUNT over several keys merges them, so a client active on several days counts once. */
    private Mono<Long> count(ClientType type, LocalDate lastDay, int days) {
        var otherDays = IntStream.range(1, days)
                .mapToObj(offset -> key(type, lastDay.minusDays(offset)))
                .toArray(String[]::new);
        return hyperLogLog(key(type, lastDay)).countWith(otherDays);
    }

    private RHyperLogLogReactive<String> hyperLogLog(String key) {
        return redisClient.getHyperLogLog(key, StringCodec.INSTANCE);
    }

    private LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private static String key(ClientType type, LocalDate day) {
        return KEY_FORMAT.formatted(type.name().toLowerCase(), day);
    }
}
