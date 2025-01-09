package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.cache.CacheManager;
import com.comet.opik.utils.JsonUtils;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

@RequiredArgsConstructor
class RedisCacheManager implements CacheManager {

    private final RedissonReactiveClient redisClient;

    public Mono<Boolean> evict(@NonNull String key) {
        return redisClient.getBucket(key).delete();
    }

    public Mono<Boolean> put(@NotNull String key, @NotNull Object value, @NotNull Duration ttlDuration) {
        return Mono.fromCallable(() -> JsonUtils.writeValueAsString(value))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(json -> redisClient.getBucket(key).set(json))
                .then(Mono.defer(() -> redisClient.getBucket(key).expire(ttlDuration)));
    }

    public <T> Mono<T> get(@NonNull String key, @NotNull Class<T> clazz) {
        return redisClient.<String>getBucket(key)
                .get()
                .filter(StringUtils::isNotEmpty)
                .flatMap(json -> Mono.fromCallable(() -> JsonUtils.readValue(json, clazz))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<Boolean> contains(@NonNull String key) {
        return redisClient.getBucket(key).isExists();
    }
}
