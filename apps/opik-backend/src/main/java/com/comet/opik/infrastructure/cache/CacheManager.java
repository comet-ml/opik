package com.comet.opik.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.NonNull;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * Cache management interface providing both reactive and non-reactive APIs.
 *
 * <p><b>API Types:</b></p>
 * <ul>
 *   <li><b>Reactive API</b>: Methods without {@code Sync} or {@code Async} suffix (e.g., {@code get()}, {@code put()})
 *      are designed for use with reactive methods that return {@code Mono} or {@code Flux}.</li>
 *   <li><b>Non-Reactive API</b>: Methods with {@code Sync} or {@code Async} suffix are designed for non-reactive methods:
 *     <ul>
 *       <li><b>Synchronous</b>: Methods with {@code Sync} suffix (e.g., {@code getSync()}) block until the result is available.</li>
 *       <li><b>Asynchronous</b>: Methods with {@code Async} suffix (e.g., {@code putAsync()}) return {@link CompletionStage} for non-blocking operations without reactiveness.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public interface CacheManager {

    // Reactive API (for reactive methods returning Mono/Flux)
    Mono<Boolean> evict(@NonNull String key, boolean usePatternMatching);
    Mono<Boolean> put(@NonNull String key, @NonNull Object value, @NonNull Duration ttlDuration);
    <T> Mono<T> get(@NonNull String key, @NonNull Class<T> clazz);
    <T> Mono<T> get(@NonNull String key, @NonNull TypeReference<T> clazz);
    Mono<Boolean> contains(@NonNull String key);

    // Non-reactive API (both synchronous and asynchronous methods without reactiveness)
    CompletionStage<Boolean> evictAsync(String key, boolean usePatternMatching);
    CompletionStage<Void> putAsync(String key, Object value, Duration ttlDuration);
    <T> T getSync(String key, Class<T> clazz);
    <T> T getSync(String key, TypeReference<T> clazz);
}
