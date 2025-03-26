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
                .map(Boolean.FALSE::equals);
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
