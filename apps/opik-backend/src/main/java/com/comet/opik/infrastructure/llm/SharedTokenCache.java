package com.comet.opik.infrastructure.llm;

import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.redis.StringRedisClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Short-lived provider credentials, such as OAuth bearers, shared by every backend pod through Redis, so that a
 * deployment makes roughly one fetch per refresh window regardless of pod count.
 *
 * <p>Values are AES-GCM encrypted in Redis. A bucket's own TTL is the point at which its value is refreshed, so
 * freshness is simply key existence and Redis's clock is the single source of truth. Cold-cache bursts collapse into a
 * single fetch via the distributed lock; on any Redis or lock failure the cache degrades to a direct fetch rather than
 * failing the call. A failed fetch is never retried here: it propagates as thrown.
 *
 * <p>The lock's lease is not renewed. It is {@code lockTimeout + fetchTimeout}, so a fetch bounded by
 * {@code fetchTimeout} leaves {@code lockTimeout} for the write. The caller's HTTP client enforces that bound; a
 * fetch outliving the lease lets a waiter fetch a second value.
 *
 * <p>{@link #getWithoutLock} skips the lock for values that pods may fetch concurrently without harm.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SharedTokenCache {

    /** A fetched value and how long other calls may be served it; zero or less is not cached at all. */
    public record Fetched(@NonNull String value, @NonNull Duration cacheFor) {
    }

    public enum Outcome {
        CACHE_HIT,
        FETCHED,
        /** The lock wait elapsed and the holder's value was found on a re-read. */
        LOCK_TIMEOUT_RECOVERED,
        /** Redis or the lock failed, or the holder left nothing behind, so this call fetched directly. */
        DEGRADED_DIRECT
    }

    public record Result(@NonNull String value, @NonNull Outcome outcome) {
    }

    private final @NonNull StringRedisClient redisClient;
    private final @NonNull LockService lockService;

    /**
     * Returns the cached value for {@code cacheKey}, or fetches, caches and returns a new one. Blocking: meant to be
     * called from a provider client's request path.
     *
     * @param lockTimeout the lease of the cross-pod fetch lock beyond {@code fetchTimeout}
     * @param fetchTimeout how long the caller's fetch may take; the lease covers it
     */
    public Result get(@NonNull String cacheKey, @NonNull Duration lockTimeout, @NonNull Duration fetchTimeout,
            @NonNull Supplier<Fetched> fetch) {
        var cached = read(cacheKey);
        if (cached != null) {
            return new Result(cached, Outcome.CACHE_HIT);
        }

        var lease = lockTimeout.plus(fetchTimeout);
        try {
            // A waiter first waits out the holder's lease, then holds the lock for at most one of its own
            var value = lockService
                    .executeWithLockCustomExpire(
                            fetchLock(cacheKey),
                            Mono.fromCallable(() -> fetchUnderLock(cacheKey, fetch)),
                            lease)
                    .timeout(lease.multipliedBy(2), Mono.empty())
                    .block();
            if (value != null) {
                return new Result(value, Outcome.FETCHED);
            }
            // Empty result = we gave up waiting for the lock holder, who has likely written the value by now, so
            // re-read before fetching. Known gap: if the holder's fetch failed, waiters still fetch directly.
            var refreshed = read(cacheKey);
            if (refreshed != null) {
                return new Result(refreshed, Outcome.LOCK_TIMEOUT_RECOVERED);
            }
            log.warn("Timed out waiting for the fetch lock, fetching directly, kind '{}'", kind(cacheKey));
        } catch (FetchFailedException failure) {
            throw failure.fetchFailure;
        } catch (RuntimeException exception) {
            log.warn("Token cache unavailable, falling back to a direct fetch, kind '{}'", kind(cacheKey),
                    withoutMessageOrCauses(exception));
        }

        return new Result(fetch.get().value(), Outcome.DEGRADED_DIRECT);
    }

    /**
     * Returns the cached value for {@code cacheKey}, or fetches, caches and returns a new one, without the cross-pod
     * lock: pods that miss at the same time each fetch. For values where that is harmless, such as OAuth tokens that
     * stay valid until their own expiry, and whose fetch the caller already single-flights per pod.
     */
    public Result getWithoutLock(@NonNull String cacheKey, @NonNull Supplier<Fetched> fetch) {
        var cached = read(cacheKey);
        if (cached != null) {
            return new Result(cached, Outcome.CACHE_HIT);
        }
        var fetched = fetch.get();
        write(cacheKey, fetched);
        return new Result(fetched.value(), Outcome.FETCHED);
    }

    /** Drops the cached value, best-effort: a Redis failure only delays the cleanup until the bucket's own expiry. */
    public void invalidate(@NonNull String cacheKey) {
        try {
            redisClient.getBucket(cacheKey).delete();
        } catch (RuntimeException exception) {
            log.warn("Failed to invalidate a cached value, kind '{}'", kind(cacheKey),
                    withoutMessageOrCauses(exception));
        }
    }

    private String fetchUnderLock(String cacheKey, Supplier<Fetched> fetch) {
        // another pod may have refreshed while this one waited on the lock
        var cached = read(cacheKey);
        if (cached != null) {
            return cached;
        }
        Fetched fetched;
        try {
            fetched = fetch.get();
        } catch (RuntimeException exception) {
            throw new FetchFailedException(exception);
        }
        write(cacheKey, fetched);
        return fetched.value();
    }

    private String read(String cacheKey) {
        try {
            var encrypted = redisClient.getBucket(cacheKey).get();
            return encrypted == null ? null : EncryptionUtils.decryptGcm(encrypted);
        } catch (RuntimeException exception) {
            // debug only: an outage also fails the lock, which get() reports once
            log.debug("Failed to read a cached value, kind '{}'", kind(cacheKey), withoutMessageOrCauses(exception));
            return null;
        }
    }

    private void write(String cacheKey, Fetched fetched) {
        if (fetched.cacheFor().isNegative() || fetched.cacheFor().isZero()) {
            return;
        }
        try {
            redisClient.getBucket(cacheKey).set(EncryptionUtils.encryptGcm(fetched.value()), fetched.cacheFor());
        } catch (RuntimeException exception) {
            log.debug("Failed to cache a value, it is still returned, kind '{}'", kind(cacheKey),
                    withoutMessageOrCauses(exception));
        }
    }

    /**
     * The lock is named by a UUID derived from the key rather than by the key: the lock service logs its name, and
     * metrics label it with the UUIDs collapsed, so the label stays one per kind.
     */
    private static LockService.Lock fetchLock(String cacheKey) {
        return new LockService.Lock(UUID.nameUUIDFromBytes(cacheKey.getBytes(StandardCharsets.UTF_8)),
                kind(cacheKey) + "-fetch-lock");
    }

    /**
     * What a key caches, its prefix up to the first {@code ':'}, for logs. The rest of the key names the Redis bucket,
     * which logs never do.
     */
    private static String kind(String cacheKey) {
        int end = cacheKey.indexOf(':');
        return end < 0 ? "token" : cacheKey.substring(0, end);
    }

    /**
     * The failure's type and stack, without its message or causes: Redisson puts the command's arguments, here the
     * encrypted value, in its exception messages.
     */
    private static RuntimeException withoutMessageOrCauses(RuntimeException exception) {
        var stripped = new RuntimeException(exception.getClass().getName());
        stripped.setStackTrace(exception.getStackTrace());
        return stripped;
    }

    /** Carries a fetch's own failure past the Redis and lock fallback, which would otherwise fetch a second time. */
    private static final class FetchFailedException extends RuntimeException {
        private final RuntimeException fetchFailure;

        private FetchFailedException(RuntimeException fetchFailure) {
            super(fetchFailure);
            this.fetchFailure = fetchFailure;
        }
    }
}
