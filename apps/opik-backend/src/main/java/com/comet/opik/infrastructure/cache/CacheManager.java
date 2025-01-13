package com.comet.opik.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.NonNull;
import reactor.core.publisher.Mono;

public interface CacheManager {

    Mono<Boolean> evict(@NonNull String group, @NonNull String key);
    Mono<Void> put(@NonNull String group, @NonNull String key, @NonNull Object value);
    <T> Mono<T> get(@NonNull String group, @NonNull String key, @NonNull Class<T> clazz);
    <T> Mono<T> get(@NonNull String group, @NonNull String key, @NonNull TypeReference<T> clazz);
    Mono<Boolean> contains(@NonNull String group, @NonNull String key);

}
