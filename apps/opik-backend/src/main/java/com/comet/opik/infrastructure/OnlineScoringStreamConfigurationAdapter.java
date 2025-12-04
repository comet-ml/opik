package com.comet.opik.infrastructure;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.infrastructure.redis.RedisStreamCodec;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.dropwizard.util.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.redisson.client.codec.Codec;

/**
 * Adapter to make OnlineScoringConfig compatible with StreamConfiguration interface.
 * This allows OnlineScoringBaseScorer to extend BaseRedisSubscriber.
 */
@RequiredArgsConstructor
public class OnlineScoringStreamConfigurationAdapter implements StreamConfiguration {

    private final @NonNull OnlineScoringConfig onlineScoringConfig;
    private final @NonNull AutomationRuleEvaluatorType evaluatorType;
    private final @NonNull OnlineScoringConfig.StreamConfiguration streamConfig;

    public static OnlineScoringStreamConfigurationAdapter create(
            @NonNull OnlineScoringConfig config,
            @NonNull AutomationRuleEvaluatorType ruleEvaluatorType) {

        var streamConfig = config.getStreams().stream()
                .filter(stream -> ruleEvaluatorType.name().equalsIgnoreCase(stream.getScorer()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No stream configuration found for evaluator type: " + ruleEvaluatorType.name()));

        return new OnlineScoringStreamConfigurationAdapter(config, ruleEvaluatorType, streamConfig);
    }

    @Override
    @JsonIgnore
    public Codec getCodec() {
        var scoringCodecs = RedisStreamCodec.fromString(streamConfig.getCodec());
        return scoringCodecs.getCodec();
    }

    @Override
    public String getStreamName() {
        return streamConfig.getStreamName();
    }

    @Override
    public int getConsumerBatchSize() {
        // Use stream-specific value if present, otherwise fall back to global value
        return streamConfig.getConsumerBatchSize() != null
                ? streamConfig.getConsumerBatchSize()
                : onlineScoringConfig.getConsumerBatchSize();
    }

    @Override
    public String getConsumerGroupName() {
        return onlineScoringConfig.getConsumerGroupName();
    }

    @Override
    public Duration getPoolingInterval() {
        // Use stream-specific value if present, otherwise fall back to global value
        return streamConfig.getPoolingInterval() != null
                ? streamConfig.getPoolingInterval()
                : onlineScoringConfig.getPoolingInterval();
    }

    @Override
    public Duration getLongPollingDuration() {
        // Use stream-specific value if present, otherwise fall back to global value
        return streamConfig.getLongPollingDuration() != null
                ? streamConfig.getLongPollingDuration()
                : onlineScoringConfig.getLongPollingDuration();
    }

    @Override
    public int getClaimIntervalRatio() {
        // Use stream-specific value if present, otherwise fall back to global value
        return streamConfig.getClaimIntervalRatio() != null
                ? streamConfig.getClaimIntervalRatio()
                : onlineScoringConfig.getClaimIntervalRatio();
    }

    @Override
    public Duration getPendingMessageDuration() {
        // Use stream-specific value if present, otherwise fall back to global value
        return streamConfig.getPendingMessageDuration() != null
                ? streamConfig.getPendingMessageDuration()
                : onlineScoringConfig.getPendingMessageDuration();
    }

    @Override
    public int getMaxRetries() {
        // Use stream-specific value if present, otherwise fall back to global value
        return streamConfig.getMaxRetries() != null
                ? streamConfig.getMaxRetries()
                : onlineScoringConfig.getMaxRetries();
    }
}
