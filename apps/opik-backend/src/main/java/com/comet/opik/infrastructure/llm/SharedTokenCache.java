package com.comet.opik.infrastructure.llm;

import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.redis.StringRedisClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * <p>The lock's lease is not renewed, so a holder still fetching after it would let a waiter take the lock and fetch a
 * second value. Every locked fetch therefore has a hard deadline, its {@code fetchTimeout}, and the lease is
 * {@code lockTimeout + fetchTimeout}, so it outlasts the fetch and leaves {@code lockTimeout} for the rest. A fetch past
 * its deadline is cancelled by interrupting its thread: the fetch runs on a virtual thread, where an interrupt closes the
 * socket it is blocked on, so the abandoned request ends there rather than at the HTTP client's own timeouts.
 *
 * <p>{@link #getWithoutLock} skips the lock for values that pods may fetch concurrently without harm.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SharedTokenCache {

    // One thread per running fetch: each blocks a caller waiting on it, so they are bounded by the callers themselves
    private static final ExecutorService FETCH_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

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

    /** A fetch ran past its deadline and was cancelled. */
    @Getter
    public static final class FetchTimeoutException extends RuntimeException {
        private final Duration timeout;

        private FetchTimeoutException(Duration timeout) {
            super("Fetch took longer than '%s'".formatted(timeout));
            this.timeout = timeout;
        }
    }

    private final @NonNull StringRedisClient redisClient;
    private final @NonNull LockService lockService;

    /**
     * Returns the cached value for {@code cacheKey}, or fetches, caches and returns a new one. Blocking: meant to be
     * called from a provider client's request path.
     *
     * @param lockTimeout the lease of the cross-pod fetch lock beyond {@code fetchTimeout}
     * @param fetchTimeout the deadline of a fetch, past which it is cancelled with {@link FetchTimeoutException}
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
                            new LockService.Lock(cacheKey + ":fetch-lock"),
                            Mono.fromCallable(() -> fetchUnderLock(cacheKey, fetchTimeout, fetch)),
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
            log.warn("Timed out waiting for the fetch lock of '{}'; fetching directly", cacheKey);
        } catch (FetchFailedException failure) {
            throw failure.fetchFailure;
        } catch (RuntimeException exception) {
            log.warn("Token cache unavailable for '{}'; falling back to a direct fetch", cacheKey,
                    withoutMessageOrCauses(exception));
        }

        return new Result(fetchWithin(fetchTimeout, fetch).value(), Outcome.DEGRADED_DIRECT);
    }

    /**
     * Returns the cached value for {@code cacheKey}, or fetches, caches and returns a new one, without the cross-pod
     * lock: pods that miss at the same time each fetch. For values where that is harmless, such as OAuth tokens that
     * stay valid until their own expiry, and whose fetch the caller already bounds and single-flights per pod.
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
            log.warn("Failed to invalidate the cached value of '{}'", cacheKey, withoutMessageOrCauses(exception));
        }
    }

    private String fetchUnderLock(String cacheKey, Duration fetchTimeout, Supplier<Fetched> fetch) {
        // another pod may have refreshed while this one waited on the lock
        var cached = read(cacheKey);
        if (cached != null) {
            return cached;
        }
        Fetched fetched;
        try {
            fetched = fetchWithin(fetchTimeout, fetch);
        } catch (RuntimeException exception) {
            throw new FetchFailedException(exception);
        }
        write(cacheKey, fetched);
        return fetched.value();
    }

    private static Fetched fetchWithin(Duration fetchTimeout, Supplier<Fetched> fetch) {
        var running = FETCH_EXECUTOR.submit(fetch::get);
        try {
            return running.get(fetchTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            running.cancel(true);
            throw new FetchTimeoutException(fetchTimeout);
        } catch (InterruptedException exception) {
            running.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a fetch", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            if (exception.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(exception.getCause());
        }
    }

    private String read(String cacheKey) {
        try {
            var encrypted = redisClient.getBucket(cacheKey).get();
            return encrypted == null ? null : EncryptionUtils.decryptGcm(encrypted);
        } catch (RuntimeException exception) {
            // debug only: an outage also fails the lock, which get() reports once
            log.debug("Failed to read the cached value of '{}'", cacheKey, withoutMessageOrCauses(exception));
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
            log.debug("Failed to cache the value of '{}'; the value is still returned", cacheKey,
                    withoutMessageOrCauses(exception));
        }
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
