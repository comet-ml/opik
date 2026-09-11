package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;

import static com.comet.opik.api.resources.utils.TestUtils.waitForMillis;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link StreamConsumerReaper} using a real Redis test container.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamConsumerReaperTest {

    private static final String GROUP = "test-group";
    private static final String PAYLOAD_FIELD = "message";

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();

    private RedissonReactiveClient redissonClient;
    private StreamConsumerReaper reaper;

    @BeforeAll
    void setUpAll() {
        redis.start();

        var redissonConfig = new Config();
        redissonConfig.useSingleServer()
                .setAddress(redis.getRedisURI())
                .setDatabase(0);
        redissonConfig.setCodec(new JsonJacksonCodec(JsonUtils.getMapper()));
        redissonClient = Redisson.create(redissonConfig).reactive();

        reaper = new StreamConsumerReaper(redissonClient);
    }

    private String streamName;
    private RStreamReactive<String, String> stream;

    @BeforeEach
    void setUp() {
        // Unique stream per test so they don't interfere; create the group (and the stream via makeStream()).
        streamName = "reaper-test-stream-%s".formatted(System.nanoTime());
        stream = redissonClient.getStream(streamName);
        stream.createGroup(StreamCreateGroupArgs.name(GROUP).makeStream()).block();
    }

    @AfterEach
    void tearDown() {
        stream.delete().block();
    }

    @Test
    void shouldReapIdleConsumerWithNoPendingEntries() {
        // A consumer that registered but never had work: idle grows, pending stays 0 — the orphan case.
        stream.createConsumer(GROUP, "orphan-consumer").block();
        assertThat(listConsumerNames()).contains("orphan-consumer");

        // Let it become idle beyond the (tiny) threshold used by this test.
        waitForMillis(600);

        var reaped = reaper.reap(List.of(streamName), Duration.ofMillis(300)).block();

        assertThat(reaped).isEqualTo(1L);
        assertThat(listConsumerNames()).doesNotContain("orphan-consumer");
    }

    @Test
    void shouldNotReapConsumerWithPendingEntries() {
        // A consumer that read a message but never acked it: idle grows but pending > 0. Its entries must be left
        // for XAUTOCLAIM to reclaim, so the reaper must NOT delete it (that would destroy the un-acked PEL entry).
        stream.add(StreamAddArgs.entry(PAYLOAD_FIELD, "unacked")).block();
        stream.readGroup(GROUP, "pending-consumer", StreamReadGroupArgs.neverDelivered().count(1)).block();

        waitForMillis(600);

        var reaped = reaper.reap(List.of(streamName), Duration.ofMillis(300)).block();

        assertThat(reaped).isZero();
        assertThat(listConsumerNames()).contains("pending-consumer");
    }

    @Test
    void shouldNotReapConsumersWithinIdleThreshold() {
        // Freshly created consumer (idle ~ 0) must survive a large idle threshold — the live-consumer case.
        stream.createConsumer(GROUP, "active-consumer").block();

        var reaped = reaper.reap(List.of(streamName), Duration.ofDays(1)).block();

        assertThat(reaped).isZero();
        assertThat(listConsumerNames()).contains("active-consumer");
    }

    @Test
    void shouldReapOnlyOrphansLeavingActiveAndPendingConsumers() {
        stream.add(StreamAddArgs.entry(PAYLOAD_FIELD, "unacked")).block();
        stream.readGroup(GROUP, "pending-consumer", StreamReadGroupArgs.neverDelivered().count(1)).block();
        stream.createConsumer(GROUP, "orphan-consumer").block();

        waitForMillis(600);

        // Created last so it is still within the idle threshold at reap time.
        stream.createConsumer(GROUP, "active-consumer").block();

        var reaped = reaper.reap(List.of(streamName), Duration.ofMillis(300)).block();

        assertThat(reaped).isEqualTo(1L);
        assertThat(listConsumerNames())
                .contains("pending-consumer", "active-consumer")
                .doesNotContain("orphan-consumer");
    }

    @Test
    void shouldReturnZeroForNonExistentStream() {
        var reaped = reaper.reap(List.of("stream-that-does-not-exist-%s".formatted(System.nanoTime())),
                Duration.ofMillis(300)).block();

        assertThat(reaped).isZero();
    }

    private List<String> listConsumerNames() {
        return stream.listConsumers(GROUP)
                .blockOptional()
                .orElse(List.of())
                .stream()
                .map(consumer -> consumer.getName())
                .toList();
    }
}
