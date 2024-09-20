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

@RequiredArgsConstructor
public class RedisRateLimitService implements RateLimitService {

    private static final String KEY = "%s:%s";

    private final RedissonReactiveClient redisClient;

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
