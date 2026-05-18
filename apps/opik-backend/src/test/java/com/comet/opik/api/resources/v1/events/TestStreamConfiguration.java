package com.comet.opik.api.resources.v1.events;

import com.comet.opik.infrastructure.StreamConfiguration;
import com.comet.opik.infrastructure.redis.RedisStreamCodec;
import io.dropwizard.util.Duration;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.RandomStringUtils;
import org.redisson.client.codec.Codec;

/**
 * Test specific implementation of StreamConfiguration for quicker test execution.
 */
@Data
@Builder(toBuilder = true)
public class TestStreamConfiguration implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";

    private static final String DEFAULT_STREAM_NAME = "test-stream";
    private static final String DEFAULT_CONSUMER_GROUP = "test-consumer-group";

    @Builder.Default
    private String streamName = DEFAULT_STREAM_NAME;

    @Builder.Default
    private String consumerGroupName = DEFAULT_CONSUMER_GROUP;

    @Builder.Default
    private int consumerBatchSize = 5;

    @Builder.Default
    private Duration poolingInterval = Duration.milliseconds(100);

    // BaseRedisSubscriber.stop() uses this as the .block() timeout when removing the consumer from
    // the group. If it expires before Redis responds, stop() disposes the consumerScheduler and
    // cancels the in-flight removeConsumer call, leaving the consumer behind. 100ms is tighter than
    // Redis-container response under load and made shouldRemoveConsumerOnStop fail 8/8 locally; 2s
    // is still well under any test wait and matches the headroom prod has at 5s.
    @Builder.Default
    private Duration longPollingDuration = Duration.seconds(2);

    @Builder.Default
    private int claimIntervalRatio = 10;

    @Builder.Default
    private Duration pendingMessageDuration = Duration.minutes(2);

    @Builder.Default
    private int maxRetries = 3;

    @Builder.Default
    private int streamMaxLen = 10000;

    @Builder.Default
    private int streamTrimLimit = 100;

    @Builder.Default
    private Codec codec = RedisStreamCodec.JAVA.getCodec();

    public static TestStreamConfiguration create() {
        return TestStreamConfiguration.builder()
                .streamName("%s-%s".formatted(
                        DEFAULT_STREAM_NAME, RandomStringUtils.secure().nextAlphanumeric(10).toLowerCase()))
                .consumerGroupName("%s-%s".formatted(
                        DEFAULT_CONSUMER_GROUP, RandomStringUtils.secure().nextAlphanumeric(10).toLowerCase()))
                .build();
    }
}
