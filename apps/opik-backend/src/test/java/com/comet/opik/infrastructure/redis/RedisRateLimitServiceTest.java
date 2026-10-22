package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RRateLimiterReactive;
import org.redisson.api.RateType;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.RedisException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisRateLimitService#isLimitExceeded} focused on the fail-open safety net:
 * when the underlying Redisson limiter errors (e.g. the 4.8.0 throttle-path "arithmetic on a nil value"
 * script desync), the service must treat the request as not limited rather than propagating the error,
 * which would surface as an HTTP 500 on ingestion. The normal allow/deny paths are asserted alongside it.
 */
@ExtendWith(MockitoExtension.class)
class RedisRateLimitServiceTest {

    private static final long EVENTS = 1;
    private static final int RANDOM_STRING_LENGTH = 16;

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private RRateLimiterReactive rateLimiter;

    private RedisRateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        when(redisClient.getRateLimiter(anyString())).thenReturn(rateLimiter);
        when(rateLimiter.trySetRate(any(RateType.class), anyLong(), any(Duration.class), any(Duration.class)))
                .thenReturn(Mono.just(true));
        rateLimitService = new RedisRateLimitService(redisClient);
    }

    private LimitConfig limitConfig() {
        return new LimitConfig("header", "bucket", 100, 60, "limit exceeded");
    }

    private String bucketName() {
        return "bucket-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(RANDOM_STRING_LENGTH));
    }

    @Test
    void isLimitExceededFailsOpenWhenLimiterScriptErrors() {
        // The throttle branch desync makes tryAcquire error; after the retries are exhausted the service
        // must emit false (not limited → request allowed) instead of propagating the error.
        when(rateLimiter.tryAcquire(EVENTS))
                .thenReturn(Mono.error(new RedisException("ERR user_script:1: arithmetic on a nil value")));

        StepVerifier.create(rateLimitService.isLimitExceeded(EVENTS, bucketName(), limitConfig()))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void isLimitExceededReturnsFalseWhenPermitAcquired() {
        // tryAcquire true → permit granted → not limited.
        when(rateLimiter.tryAcquire(EVENTS)).thenReturn(Mono.just(true));

        StepVerifier.create(rateLimitService.isLimitExceeded(EVENTS, bucketName(), limitConfig()))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void isLimitExceededReturnsTrueWhenPermitDenied() {
        // tryAcquire false → permit denied → limited (normal 429 path, unchanged).
        when(rateLimiter.tryAcquire(EVENTS)).thenReturn(Mono.just(false));

        StepVerifier.create(rateLimitService.isLimitExceeded(EVENTS, bucketName(), limitConfig()))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void setLimitUsesKeepAliveTimeSoBucketKeysShareOneTtl() {
        // Root-cause fix: keepAliveTime (== the rate interval) is handed to Redisson so the config hash,
        // the permit counter and the permits sorted-set are pexpired together inside the acquire script;
        // the out-of-band expireIfNotSet that let them diverge (OPIK-8923) is gone.
        when(rateLimiter.tryAcquire(EVENTS)).thenReturn(Mono.just(true));
        LimitConfig config = limitConfig();
        Duration interval = Duration.ofSeconds(config.durationInSeconds());

        StepVerifier.create(rateLimitService.isLimitExceeded(EVENTS, bucketName(), config))
                .expectNext(false)
                .verifyComplete();

        verify(rateLimiter).trySetRate(RateType.OVERALL, config.limit(), interval, interval);
        verify(rateLimiter, never()).expireIfNotSet(any(Duration.class));
    }
}
