package com.comet.opik.infrastructure.ratelimit;

import reactor.core.publisher.Mono;

public interface RateLimitService {

    Mono<Boolean> isLimitExceeded(String apiKey, long events, String bucketName, long limit,
            long limitDurationInSeconds);

    Mono<Long> availableEvents(String apiKey, String bucketName);

    Mono<Long> getRemainingTTL(String apiKey, String bucket);
}
