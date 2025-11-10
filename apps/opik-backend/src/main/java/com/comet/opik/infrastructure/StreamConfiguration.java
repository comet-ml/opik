package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.dropwizard.util.Duration;
import org.redisson.client.codec.Codec;

public interface StreamConfiguration {

    @JsonIgnore
    Codec getCodec();

    String getStreamName();

    int getConsumerBatchSize();

    String getConsumerGroupName();

    Duration getPoolingInterval();

    Duration getLongPollingDuration();

    int getClaimIntervalRatio();

    Duration getPendingMessageDuration();

    int getMaxRetries();
}
