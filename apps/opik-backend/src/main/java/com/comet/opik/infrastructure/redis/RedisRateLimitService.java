package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiterReactive;
import org.redisson.api.RateType;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.RedisException;
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
                // keeps writes flowing. keepAliveTime (see setLimitIfNecessary) closes the acquire-path
                // desync, but Redisson's availablePermits script, which we call for the response headers,
                // still rewrites the counter with a plain SET that drops its TTL, so the counter can outlive
                // the permits set and the error can recur; this fail-open is the backstop for that. The error
                // surfaces here as a RetryExhaustedException wrapping the RedisException, so catch broadly
                // rather than by type.
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
        // the rate interval, so idle buckets are still reclaimed after one interval of inactivity.
        // This does not cover the header path: Redisson's availablePermits script rewrites the counter with a
        // plain SET and no pexpire, so the divergence can still recur there; isLimitExceeded fails open for it.
        Duration interval = Duration.ofSeconds(limitDurationInSeconds);
        return rateLimit.trySetRate(RateType.OVERALL, limit, interval, interval);
    }

    // availableEvents and getRemainingTTL feed the rate-limit response headers, which the interceptor sets
    // from a finally block. A limiter error here must stay best-effort and never replace the request's real
    // outcome with a 500, so both fall back to a safe value rather than propagating (OPIK-8923). They run on
    // every request, so the fallback logs at debug without the stack trace: during a Redis outage a warn with
    // a full trace per request would amplify the incident. The acquire-path fail-open keeps its full trace.

    @Override
    public Mono<Long> availableEvents(@NonNull String bucketName, @NonNull LimitConfig limitConfig) {
        RRateLimiterReactive rateLimit = redisClient.getRateLimiter(bucketName);
        return setLimitIfNecessary(limitConfig.limit(), limitConfig.durationInSeconds(), rateLimit)
                .then(Mono.defer(rateLimit::availablePermits).retryWhen(configureRetry(limitConfig, rateLimit)))
                .onErrorResume(error -> {
                    log.debug("Rate limiter available-events lookup failed, reporting the configured limit: {}",
                            error.getMessage());
                    return Mono.just(limitConfig.limit());
                });
    }

    @Override
    public Mono<Long> getRemainingTTL(@NonNull String bucketName, @NonNull LimitConfig limitConfig) {
        RRateLimiterReactive rateLimit = redisClient.getRateLimiter(bucketName);
        return setLimitIfNecessary(limitConfig.limit(), limitConfig.durationInSeconds(), rateLimit)
                .then(Mono.defer(rateLimit::remainTimeToLive).retryWhen(configureRetry(limitConfig, rateLimit)))
                .onErrorResume(error -> {
                    // Report the full window, not 0: the interceptor turns 0 into a RateLimit-Reset of 1s,
                    // which would make a rejected client retry after a second and spin on 429s.
                    log.debug("Rate limiter TTL lookup failed, reporting the configured window: {}",
                            error.getMessage());
                    return Mono.just(Duration.ofSeconds(limitConfig.durationInSeconds()).toMillis());
                });
    }

}
