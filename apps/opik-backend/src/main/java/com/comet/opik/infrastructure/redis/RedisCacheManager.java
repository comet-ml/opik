package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.CacheConfiguration;
import com.comet.opik.infrastructure.cache.CacheManager;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.CacheReactive;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
class RedisCacheManager implements CacheManager {

    private final @NonNull javax.cache.CacheManager cacheManager;
    private final @NonNull CacheConfiguration cacheConfiguration;

    private static MutableConfiguration<String, String> getConfiguration(CacheConfiguration cacheConfiguration, String group) {
        MutableConfiguration<String, String> config = new MutableConfiguration<>();
        Duration defaultDuration = cacheConfiguration.getDefaultDuration();
        long millis = cacheConfiguration.getCaches().getOrDefault(group, defaultDuration).toMillis();
        config.setExpiryPolicyFactory(CreatedExpiryPolicy.factoryOf(new javax.cache.expiry.Duration(TimeUnit.MILLISECONDS, millis)));
        config.setStoreByValue(true);
        config.setManagementEnabled(true);
        config.setStatisticsEnabled(true);
        return config;
    }

    public Mono<Boolean> evict(@NonNull String group, @NonNull String key) {
        return getCache(group)
                .flatMap(cache -> cache.remove(key));
    }

    private Mono<CacheReactive<String, String>> getCache(@NotNull String group) {
        MutableConfiguration<String, String> configuration = getConfiguration(cacheConfiguration, group);
        return Mono.fromCallable(() -> cacheManager.getCache(group).unwrap(CacheReactive.class))
                .onErrorResume(e -> Mono.fromCallable(() -> cacheManager.createCache(group, configuration).unwrap(CacheReactive.class))
                        .onErrorResume(ex -> Mono.fromCallable(() -> cacheManager.getCache(group).unwrap(CacheReactive.class))))
                .map(cache -> (CacheReactive<String, String>) cache)
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> put(@NonNull String group, @NonNull String key, @NonNull Object value) {
        return Mono.fromCallable(() -> JsonUtils.writeValueAsString(value))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(json -> getCache(group)
                        .flatMap(cache -> cache.put(key, json)));
    }

    public <T> Mono<T> get(@NonNull String group, @NonNull String key, @NonNull Class<T> clazz) {
        return getCache(group)
                .flatMap(cache -> cache.get(key))
                .filter(StringUtils::isNotEmpty)
                .flatMap(json -> Mono.fromCallable(() -> JsonUtils.readValue(json, clazz))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public <T> Mono<T> get(@NonNull String group, @NonNull String key, @NonNull TypeReference<T> typeReference) {
        return getCache(group)
                .flatMap(cache -> cache.get(key))
                .filter(StringUtils::isNotEmpty)
                .flatMap(json -> Mono.fromCallable(() -> JsonUtils.readValue(json, typeReference))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<Boolean> contains(@NonNull String group, @NonNull String key) {
        return getCache(group).flatMap(cache -> cache.containsKey(key));
    }
}
