package com.comet.opik.infrastructure.ratelimit;

import reactor.core.publisher.Mono;

import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;

public interface RateLimitService {

    Mono<Boolean> isLimitExceeded(long events, String bucketName, LimitConfig limitConfig);

    Mono<Long> availableEvents(String bucketName, LimitConfig limitConfig);

    Mono<Long> getRemainingTTL(String bucket, LimitConfig limitConfig);
}
