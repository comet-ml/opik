package com.comet.opik.api.resources.v1.events;

import com.comet.opik.infrastructure.StreamConfiguration;
import io.dropwizard.util.Duration;
import lombok.Builder;
import lombok.Data;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;

/**
 * Test-specific implementation of StreamConfiguration with fast polling intervals
 * and small batch sizes for quicker test execution.
 */
@Data
@Builder
public class TestStreamConfiguration implements StreamConfiguration {

    public static final String DEFAULT_STREAM_NAME = "test-stream";
    public static final String DEFAULT_CONSUMER_GROUP = "test-consumer-group";
    public static final String PAYLOAD_FIELD = "message";

    @Builder.Default
    private String streamName = DEFAULT_STREAM_NAME;

    @Builder.Default
    private String consumerGroupName = DEFAULT_CONSUMER_GROUP;

    @Builder.Default
    private int consumerBatchSize = 10;

    @Builder.Default
    private Duration poolingInterval = Duration.milliseconds(200);

    @Builder.Default
    private Duration longPollingDuration = Duration.milliseconds(500);

    @Builder.Default
    private Codec codec = new StringCodec();

    @Override
    public Codec getCodec() {
        return codec;
    }

    public static TestStreamConfiguration createWithFastPolling() {
        return TestStreamConfiguration.builder()
                .poolingInterval(Duration.milliseconds(100))
                .longPollingDuration(Duration.milliseconds(300))
                .build();
    }
}
