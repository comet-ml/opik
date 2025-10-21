package com.comet.opik.api.resources.v1.events;

import com.comet.opik.infrastructure.StreamConfiguration;
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

    @Builder.Default
    private Duration longPollingDuration = Duration.milliseconds(300);

    @Builder.Default
    private Codec codec = OnlineScoringCodecs.JAVA.getCodec();

    public static TestStreamConfiguration create() {
        return TestStreamConfiguration.builder()
                .streamName("%s-%s".formatted(
                        DEFAULT_STREAM_NAME, RandomStringUtils.secure().nextAlphanumeric(10).toLowerCase()))
                .consumerGroupName("%s-%s".formatted(
                        DEFAULT_CONSUMER_GROUP, RandomStringUtils.secure().nextAlphanumeric(10).toLowerCase()))
                .build();
    }

    public static TestStreamConfiguration createForDependencyInjection() {
        return create().toBuilder()
                .consumerBatchSize(1)
                .poolingInterval(Duration.minutes(1))
                .longPollingDuration(Duration.seconds(5))
                .build();
    }
}
