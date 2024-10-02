package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RRateLimiterReactive;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;

@RequiredArgsConstructor
public class RedisRateLimitService implements RateLimitService {

    private static final String KEY = "%s:%s";

    private final RedissonReactiveClient redisClient;

    @Override
    public Mono<Boolean> isLimitExceeded(@NonNull String apiKey, long events, @NonNull String bucketName, @NonNull LimitConfig limitConfig) {

        RRateLimiterReactive rateLimit = redisClient.getRateLimiter(KEY.formatted(bucketName, apiKey));

        return setLimitIfNecessary(limitConfig.limit(), limitConfig.durationInSeconds(), rateLimit)
                .then(Mono.defer(() -> rateLimit.tryAcquire(events)))
                .map(Boolean.FALSE::equals);
    }

    private Mono<Boolean> setLimitIfNecessary(long limit, long limitDurationInSeconds, RRateLimiterReactive rateLimit) {
        return rateLimit.isExists()
                .flatMap(exists -> Boolean.TRUE.equals(exists) ? Mono.empty() : rateLimit.trySetRate(RateType.OVERALL, limit, limitDurationInSeconds, RateIntervalUnit.SECONDS))
                .then(Mono.defer(() -> rateLimit.expireIfNotSet(Duration.ofSeconds(limitDurationInSeconds))));
    }

    @Override
    public Mono<Long> availableEvents(@NonNull String apiKey, @NonNull String bucketName, @NonNull LimitConfig limitConfig) {
        RRateLimiterReactive rateLimit = redisClient.getRateLimiter(KEY.formatted(bucketName, apiKey));
        return setLimitIfNecessary(limitConfig.limit(), limitConfig.durationInSeconds(), rateLimit)
                .then(Mono.defer(rateLimit::availablePermits));
    }

    @Override
    public Mono<Long> getRemainingTTL(@NonNull String apiKey, @NonNull String bucketName, @NonNull LimitConfig limitConfig) {
        RRateLimiterReactive rateLimit = redisClient.getRateLimiter(KEY.formatted(bucketName, apiKey));
        return setLimitIfNecessary(limitConfig.limit(), limitConfig.durationInSeconds(), rateLimit)
                .then(Mono.defer(rateLimit::remainTimeToLive));
    }

}
