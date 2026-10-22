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
                // keeps writes flowing. The error surfaces here as a RetryExhaustedException wrapping the
                // RedisException, so catch broadly rather than by type. The desync root cause (atomic Lua /
                // Redisson bump) is tracked as a follow-up in OPIK-8923.
                .onErrorResume(error -> {
                    log.warn("Rate limiter errored for bucket '{}', failing open (request allowed): {}", bucketName,
                            error.getMessage());
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
        return rateLimit.trySetRate(RateType.OVERALL, limit, Duration.ofSeconds(limitDurationInSeconds))
                .flatMap(__ -> rateLimit.expireIfNotSet(Duration.ofSeconds(limitDurationInSeconds)));
    }

    @Override
    public Mono<Long> availableEvents(@NonNull String bucketName, @NonNull LimitConfig limitConfig) {
        RRateLimiterReactive rateLimit = redisClient.getRateLimiter(bucketName);
        return setLimitIfNecessary(limitConfig.limit(), limitConfig.durationInSeconds(), rateLimit)
                .then(Mono.defer(rateLimit::availablePermits).retryWhen(configureRetry(limitConfig, rateLimit)));
    }

    @Override
    public Mono<Long> getRemainingTTL(@NonNull String bucketName, @NonNull LimitConfig limitConfig) {
        RRateLimiterReactive rateLimit = redisClient.getRateLimiter(bucketName);
        return setLimitIfNecessary(limitConfig.limit(), limitConfig.durationInSeconds(), rateLimit)
                .then(Mono.defer(rateLimit::remainTimeToLive).retryWhen(configureRetry(limitConfig, rateLimit)));
    }

}
