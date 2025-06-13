package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import org.redisson.client.codec.Codec;

public interface StreamConfiguration {

    Codec getCodec();

    String getStreamName();

    int getConsumerBatchSize();

    String getConsumerGroupName();

    Duration getPoolingInterval();
}
