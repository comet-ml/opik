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

    /**
     * Time before a message is considered orphaned and can be claimed by another consumer.
     * Messages that remain in the pending list longer than this duration will be reclaimed.
     *
     * @return the timeout duration for pending messages
     */
    Duration getPendingMessageTimeout();

    /**
     * Interval in polling ticks between pending message claim attempts.
     * For example, if set to 10, pending messages will be claimed every 10 polling intervals.
     *
     * @return the number of polling ticks between claim attempts
     */
    int getClaimIntervalTicks();

    /**
     * Maximum number of processing attempts before a message is sent to the dead letter queue.
     *
     * @return the maximum retry attempts
     */
    int getMaxRetryAttempts();
}
