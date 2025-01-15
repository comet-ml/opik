package com.comet.opik.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.NonNull;
import reactor.core.publisher.Mono;

import java.time.Duration;

public interface CacheManager {

    Mono<Boolean> evict(@NonNull String key, boolean usePatternMatching);
    Mono<Boolean> put(@NonNull String key, @NonNull Object value, @NonNull Duration ttlDuration);
    <T> Mono<T> get(@NonNull String key, @NonNull Class<T> clazz);
    <T> Mono<T> get(@NonNull String key, @NonNull TypeReference<T> clazz);
    Mono<Boolean> contains(@NonNull String key);

}
