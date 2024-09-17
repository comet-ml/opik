package com.comet.opik.infrastructure.ratelimit;

import reactor.core.publisher.Mono;

public interface RateLimitService {

    Mono<Boolean> isLimitExceeded(String apiKey, String bucketName, long limit, long limitDurationInSeconds);
}
