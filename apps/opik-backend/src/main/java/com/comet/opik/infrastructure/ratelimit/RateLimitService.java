package com.comet.opik.infrastructure.ratelimit;

import reactor.core.publisher.Mono;

import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;

public interface RateLimitService {

    Mono<Boolean> isLimitExceeded(String apiKey, long events, String bucketName, LimitConfig limitConfig);

    Mono<Long> availableEvents(String apiKey, String bucketName, LimitConfig limitConfig);

    Mono<Long> getRemainingTTL(String apiKey, String bucket, LimitConfig limitConfig);
}
