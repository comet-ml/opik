package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import org.redisson.api.RAtomicLongReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

public class RedisRateLimitService implements RateLimitService {

    private final RedissonReactiveClient redisClient;

    public RedisRateLimitService(RedissonReactiveClient redisClient) {
        this.redisClient = redisClient;
    }

    @Override
    public Mono<Boolean> isLimitExceeded(String apiKey, String bucketName, long limit, long limitDurationInSeconds) {

        RAtomicLongReactive limitInstance = redisClient.getAtomicLong(bucketName + ":" + apiKey);

        return limitInstance
                .incrementAndGet()
                .flatMap(count -> {

                    if (count == 1) {
                        return limitInstance.expire(Duration.ofSeconds(limitDurationInSeconds))
                                .map(__ -> count > limit);
                    }

                    return Mono.just(count > limit);
                });
    }

    @Override
    public Mono<Void> decrement(String apiKey, String bucketName) {
        RAtomicLongReactive limitInstance = redisClient.getAtomicLong(bucketName + ":" + apiKey);
        return limitInstance.decrementAndGet().then();
    }

}
