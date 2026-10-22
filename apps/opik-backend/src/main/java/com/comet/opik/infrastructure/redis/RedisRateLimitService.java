package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucketReactive;
import org.redisson.api.RRateLimiterReactive;
import org.redisson.api.RateType;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.LongCodec;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;

@RequiredArgsConstructor
@Slf4j
class RedisRateLimitService implements RateLimitService {

    private final RedissonReactiveClient redisClient;

    @Override
    public Mono<Boolean> isLimitExceeded(long events, @NonNull String bucketName, @NonNull LimitConfig limitConfig) {

        RRateLimiterReactive rateLimit = redisClient.getRateLimiter(bucketName);

        return setLimitIfNecessary(limitConfig.limit(), limitConfig.durationInSeconds(), rateLimit)
                .then(Mono.defer(() -> rateLimit.tryAcquire(events).retryWhen(configureRetry(limitConfig, rateLimit))))
                .map(Boolean.FALSE::equals)
                // Fail open: once the retries above are exhausted, a limiter/Lua script failure must not take
                // down ingestion. Redisson 4.8.0's throttle branch can error with "arithmetic on a nil value"
                // when the permit counter and the permits sorted-set desync; treating that as "not limited"
                // keeps writes flowing. keepAliveTime (see setLimitIfNecessary) keeps the acquire-path keys on
                // one TTL and availableEvents now reads the counter directly instead of the mutating
                // availablePermits, so no code path strips the counter's TTL any more; this fail-open stays as
                // defense-in-depth for any other limiter/Lua failure. The error surfaces here as a
                // RetryExhaustedException wrapping the RedisException, so catch broadly rather than by type.
                .onErrorResume(error -> {
                    // Don't log bucketName: for the general and custom buckets it embeds the caller's API
                    // key. Pass error as the throwable so the full stack trace is kept, not just its message.
                    log.warn("Rate limiter errored, failing open (request allowed)", error);
                    return Mono.just(false);
                });
    }

    private Retry configureRetry(LimitConfig limitConfig, RRateLimiterReactive rateLimit) {
        return Retry.fixedDelay(2, Duration.ofMillis(5))
                .filter(RedisException.class::isInstance)
                .doBeforeRetryAsync(signal -> {
                    log.warn("Retrying due to error", signal.failure());
                    return setLimitIfNecessary(limitConfig.limit(), limitConfig.durationInSeconds(), rateLimit).then();
                });
    }

    private Mono<Boolean> setLimitIfNecessary(long limit, long limitDurationInSeconds, RRateLimiterReactive rateLimit) {
        // keepAliveTime makes Redisson pexpire the config hash, the permit counter and the permits
        // sorted-set to the same TTL inside the atomic acquire script, so they can no longer expire
        // independently on the acquire path. The previous out-of-band expireIfNotSet only put a TTL on the
        // config hash and let the value/permits keys diverge, producing the empty-permits/exhausted-counter
        // state that makes the throttle branch error with "arithmetic on a nil value" (OPIK-8923). It equals
        // the rate interval, so idle buckets are still reclaimed after one interval of inactivity. The header
        // path used to reopen this by calling the mutating availablePermits; availableEvents now reads the
        // counter read-only instead, so no path strips the counter's TTL.
        Duration interval = Duration.ofSeconds(limitDurationInSeconds);
        return rateLimit.trySetRate(RateType.OVERALL, limit, interval, interval);
    }

    // availableEvents and getRemainingTTL feed the rate-limit response headers, which the interceptor sets
    // from a finally block. They run on every request and must be read-only and best-effort: a limiter error
    // here must never replace the request's real outcome with a 500 (OPIK-8923), so both fall back to a safe
    // value, and the fallback logs at debug without the stack trace so a Redis outage can't amplify into a
    // per-request stack-trace storm. The acquire-path fail-open keeps its full trace.

    @Override
    public Mono<Long> availableEvents(@NonNull String bucketName, @NonNull LimitConfig limitConfig) {
        // Read the counter directly, read-only. NOT RRateLimiter.availablePermits(): its Lua rewrites the
        // counter with a plain SET and no pexpire, which strips the key's TTL and reopens the OPIK-8923
        // desync. The counter is the permits left in the window; an absent key means a fresh/idle bucket, so
        // the full limit is available. This is a conservative floor — it can under-report until the next
        // acquire runs Redisson's permit release — which is acceptable for a header.
        return permitCounter(bucketName).get()
                .defaultIfEmpty(limitConfig.limit())
                .onErrorResume(error -> {
                    // Log only the exception type, not the raw Redis message, to keep this per-request debug
                    // line fixed-size and free of unbounded server text.
                    log.debug("Rate limiter available-events lookup failed ({}), reporting the configured limit",
                            error.getClass().getSimpleName());
                    return Mono.just(limitConfig.limit());
                });
    }

    @Override
    public Mono<Long> getRemainingTTL(@NonNull String bucketName, @NonNull LimitConfig limitConfig) {
        // remainTimeToLive is a read-only PTTL on the limiter's config hash (RedissonExpirable); it does not
        // rewrite the counter, so unlike availablePermits it never strips the counter's TTL. setLimitIfNecessary
        // stays so an expired bucket is re-initialised and the reset header reflects a fresh window rather than
        // "key missing".
        RRateLimiterReactive rateLimit = redisClient.getRateLimiter(bucketName);
        return setLimitIfNecessary(limitConfig.limit(), limitConfig.durationInSeconds(), rateLimit)
                .then(Mono.defer(rateLimit::remainTimeToLive).retryWhen(configureRetry(limitConfig, rateLimit)))
                .onErrorResume(error -> {
                    // Report the full window, not 0: the interceptor turns 0 into a RateLimit-Reset of 1s,
                    // which would make a rejected client retry after a second and spin on 429s. Log only the
                    // exception type, not the raw Redis message, to keep this debug line fixed-size.
                    log.debug("Rate limiter TTL lookup failed ({}), reporting the configured window",
                            error.getClass().getSimpleName());
                    return Mono.just(Duration.ofSeconds(limitConfig.durationInSeconds()).toMillis());
                });
    }

    // Redisson 4.8.0 keeps the live permit counter in a String at the rate limiter's value key, named by
    // RedissonObject.suffixName: "{name}:value", or "name:value" when the name already holds a hash tag.
    // We bind to that layout on purpose so the header path can read the counter without the mutating
    // availablePermits script (see availableEvents). Re-verify this naming if Redisson is upgraded (OPIK-8923).
    private RBucketReactive<Long> permitCounter(@NonNull String bucketName) {
        String valueKey = bucketName.contains("{") ? bucketName + ":value" : "{" + bucketName + "}:value";
        return redisClient.getBucket(valueKey, LongCodec.INSTANCE);
    }

}
