package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucketReactive;
import org.redisson.api.RRateLimiterReactive;
import org.redisson.api.RateType;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.Codec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisRateLimitService}. Two concerns are covered: the acquire-path fail-open safety net
 * (a Redisson limiter error, e.g. the 4.8.0 "arithmetic on a nil value" desync, must be treated as "not limited"
 * rather than surfacing as an HTTP 500 on ingestion), and the header path being read-only — availableEvents must
 * read the counter without invoking the mutating availablePermits script that strips the key's TTL (OPIK-8923).
 */
@ExtendWith(MockitoExtension.class)
class RedisRateLimitServiceTest {

    private static final long EVENTS = 1;
    private static final int RANDOM_STRING_LENGTH = 16;

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private RRateLimiterReactive rateLimiter;

    @Mock
    private RBucketReactive<Long> valueBucket;

    private RedisRateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        // Lenient: the acquire-path tests use the limiter, the read-only header-path tests use the bucket.
        lenient().when(redisClient.getRateLimiter(anyString())).thenReturn(rateLimiter);
        lenient().when(rateLimiter.trySetRate(any(RateType.class), anyLong(), any(Duration.class), any(Duration.class)))
                .thenReturn(Mono.just(true));
        lenient().when(rateLimiter.expireIfNotSet(any(Duration.class))).thenReturn(Mono.just(true));
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
    void setLimitUsesKeepAliveTimeAndExpireIfNotSetSoBucketKeysAlwaysExpire() {
        // keepAliveTime (== the rate interval) keeps the limiter keys on one TTL inside the acquire script;
        // expireIfNotSet puts a TTL on any key that lost it, so a bucket the acquire script can't fix expires.
        when(rateLimiter.tryAcquire(EVENTS)).thenReturn(Mono.just(true));
        LimitConfig config = limitConfig();
        Duration interval = Duration.ofSeconds(config.durationInSeconds());

        StepVerifier.create(rateLimitService.isLimitExceeded(EVENTS, bucketName(), config))
                .expectNext(false)
                .verifyComplete();

        verify(rateLimiter).trySetRate(RateType.OVERALL, config.limit(), interval, interval);
        verify(rateLimiter).expireIfNotSet(interval);
    }

    @Test
    void availableEventsReadsCounterWithoutWriting() {
        // The header value is a plain read of the counter key: no SET/EXPIRE on it, and never the mutating
        // availablePermits() that would strip its TTL (OPIK-8923).
        when(redisClient.<Long>getBucket(anyString(), any(Codec.class))).thenReturn(valueBucket);
        when(valueBucket.get()).thenReturn(Mono.just(7L));

        StepVerifier.create(rateLimitService.availableEvents(bucketName(), limitConfig()))
                .expectNext(7L)
                .verifyComplete();

        verify(valueBucket).get();
        verifyNoMoreInteractions(valueBucket);
        verify(rateLimiter, never()).availablePermits();
        verify(redisClient, never()).getRateLimiter(anyString());
    }

    @Test
    void availableEventsReturnsLimitWhenCounterAbsent() {
        // Absent counter key = fresh/idle bucket → report the full configured limit.
        when(redisClient.<Long>getBucket(anyString(), any(Codec.class))).thenReturn(valueBucket);
        when(valueBucket.get()).thenReturn(Mono.empty());
        LimitConfig config = limitConfig();

        StepVerifier.create(rateLimitService.availableEvents(bucketName(), config))
                .expectNext(config.limit())
                .verifyComplete();
    }

    @Test
    void availableEventsFailsSafeWhenLimiterErrors() {
        // Header metadata runs in the interceptor's finally; a read error must not escape as a 500.
        when(redisClient.<Long>getBucket(anyString(), any(Codec.class))).thenReturn(valueBucket);
        when(valueBucket.get()).thenReturn(Mono.error(new RedisException("boom")));
        LimitConfig config = limitConfig();

        StepVerifier.create(rateLimitService.availableEvents(bucketName(), config))
                .expectNext(config.limit())
                .verifyComplete();
    }

    @Test
    void getRemainingTtlFailsSafeWhenLimiterErrors() {
        // Fallback reports the full window (ms), not 0, so the interceptor doesn't advertise a 1s reset.
        when(rateLimiter.remainTimeToLive()).thenReturn(Mono.error(new RedisException("boom")));
        LimitConfig config = limitConfig();

        StepVerifier.create(rateLimitService.getRemainingTTL(bucketName(), config))
                .expectNext(Duration.ofSeconds(config.durationInSeconds()).toMillis())
                .verifyComplete();
    }
}
