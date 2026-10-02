package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfigTest;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flush limit across backend instances, on a real Redis: two readers, standing for two instances, share the
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
        var permit = FreeFormSqlQueryLogReader.permit(rateLimit, "flush-" + UUID.randomUUID(),
                FreeFormSqlPostRunCheckConfigTest.config());
        var starts = new CopyOnWriteArrayList<Long>();
        var instances = IntStream.range(0, 2).mapToObj(i -> new FreeFormSqlQueryLogReader(
                (queryId, user) -> CompletableFuture.completedFuture(List.<FreeFormSqlQueryLogEntry>of()),
                () -> {
                    starts.add(System.currentTimeMillis());
                    return CompletableFuture.<Void>completedFuture(null);
                }, permit, 3, 50, FreeFormSqlQueryLogReader.Scheduler.DELAYED))
                .toList();

        // The entry never appears, so every instance asks for flushes continuously for about three seconds.
        long start = System.nanoTime();
        long end = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < end) {
            CompletableFuture.allOf(instances.stream().map(reader -> reader.entries("q", "u", true))
                    .toArray(CompletableFuture[]::new)).join();
        }
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

        // The limiter hands out one permit per second, so the run gets at most one per started second.
        assertThat(starts).as("flushes happened").hasSizeGreaterThanOrEqualTo(2);
        assertThat(starts).as("flushes across both instances in %d s", elapsedSeconds)
                .hasSizeLessThanOrEqualTo((int) elapsedSeconds + 1);
        // And no two close together: without the limiter they land milliseconds apart. Half the interval leaves
        // room for runner and network jitter around the one-second refill.
        List<Long> sorted = starts.stream().sorted().toList();
        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i) - sorted.get(i - 1)).as("gap between flush %d and %d", i, i + 1)
                    .isGreaterThanOrEqualTo(500);
        }
    }
}
