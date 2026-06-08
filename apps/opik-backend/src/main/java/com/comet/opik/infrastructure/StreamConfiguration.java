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

    int getStreamMaxLen();

    int getStreamTrimLimit();

    /**
     * Upper bound, in bytes, on the estimated in-flight payload a single consumer may process
     * concurrently. {@code 0} (the default) disables the memory-aware admission gate, preserving
     * the count-only concurrency behavior. Only the online-scoring streams override this.
     */
    default long getMaxInFlightBytes() {
        return 0;
    }
}
