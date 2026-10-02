package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.RateLimitConfig;
import com.comet.opik.infrastructure.redis.RedisModule;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flush limit across backend instances, on a real Redis: two flushers, standing for two instances, share the
 * cluster-wide permit, so their flushes together stay at one per interval.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Free-form SQL query log flush limit, cluster-wide")
class FreeFormSqlQueryLogFlushClusterTest {

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private RedissonClient redisson;

    @BeforeAll
    void setUpAll() {
        redis.start();
        var config = new Config();
        config.useSingleServer().setAddress(redis.getRedisURI()).setDatabase(0);
        redisson = Redisson.create(config);
    }

    @AfterAll
    void tearDownAll() {
        redisson.shutdown();
    }

    @Test
    @DisplayName("two instances flushing as often as asked still flush at most once per second between them")
    void flushesStayAtOnePerSecondAcrossInstances() {
        var rateLimit = new RedisModule().rateLimitService(redisson.reactive());
        // A bucket of its own, so a reused Redis cannot hand this run another run's permit.
        var permits = new RateLimitConfig.LimitConfig("unused", "flush-" + UUID.randomUUID(), 1, 1, "");
        var starts = new CopyOnWriteArrayList<Long>();
        var instances = IntStream.range(0, 2).mapToObj(i -> new FreeFormSqlQueryLogFlusher(() -> {
            starts.add(System.currentTimeMillis());
            return CompletableFuture.<Void>completedFuture(null);
        }, 0, 50, System::currentTimeMillis, FreeFormSqlQueryLogFlusher.Scheduler.DELAYED,
                () -> rateLimit.isLimitExceeded(1, permits.userFacingBucketName(), permits).map(exceeded -> !exceeded)
                        .toFuture()))
                .toList();

        // Every instance asks for a flush continuously for about three seconds.
        long end = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < end) {
            CompletableFuture.allOf(instances.stream().map(FreeFormSqlQueryLogFlusher::awaitFlush)
                    .toArray(CompletableFuture[]::new)).join();
        }

        List<Long> sorted = starts.stream().sorted().toList();
        assertThat(sorted).as("flushes happened").hasSizeGreaterThanOrEqualTo(2);
        for (int i = 1; i < sorted.size(); i++) {
            // The limiter refills one permit per second; allow a little clock and network jitter.
            assertThat(sorted.get(i) - sorted.get(i - 1)).as("gap between flush %d and %d", i, i + 1)
                    .isGreaterThanOrEqualTo(900);
        }
    }
}
