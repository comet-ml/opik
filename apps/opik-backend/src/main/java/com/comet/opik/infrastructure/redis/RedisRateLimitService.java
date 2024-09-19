package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import lombok.NonNull;
import org.redisson.api.RRateLimiterReactive;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

public class RedisRateLimitService implements RateLimitService {

    private static final String KEY = "%s:%s";

    private final RedissonReactiveClient redisClient;

    public RedisRateLimitService(RedissonReactiveClient redisClient) {
        this.redisClient = redisClient;
    }

    @Override
    public Mono<Boolean> isLimitExceeded(String apiKey, long events, String bucketName, long limit,
            long limitDurationInSeconds) {

        RRateLimiterReactive rateLimit = redisClient.getRateLimiter(KEY.formatted(bucketName, apiKey));

        return rateLimit.trySetRate(RateType.OVERALL, limit, limitDurationInSeconds, RateIntervalUnit.SECONDS)
                .then(Mono.defer(() -> rateLimit.expireIfNotSet(Duration.ofSeconds(limitDurationInSeconds))))
                .then(Mono.defer(() -> rateLimit.tryAcquire(events)))
                .map(Boolean.FALSE::equals);
    }

    @Override
    public Mono<Void> decrement(@NonNull String apiKey, @NonNull String bucketName, long events) {
        RRateLimiterReactive rateLimit = redisClient.getRateLimiter(KEY.formatted(bucketName, apiKey));
        return rateLimit.tryAcquire(-events).then();
    }

    @Override
    public Mono<Long> availableEvents(@NonNull String apiKey, @NonNull String bucketName) {
        RRateLimiterReactive rateLimit = redisClient.getRateLimiter(KEY.formatted(bucketName, apiKey));
        return rateLimit.availablePermits();
    }

    @Override
    public Mono<Long> getRemainingTTL(@NonNull String apiKey, @NonNull String bucketName) {
        RRateLimiterReactive rateLimit = redisClient.getRateLimiter(KEY.formatted(bucketName, apiKey));
        return rateLimit.remainTimeToLive();
    }

}
