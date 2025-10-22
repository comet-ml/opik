package com.comet.opik.infrastructure;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.resources.v1.events.OnlineScoringCodecs;
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
            @NonNull AutomationRuleEvaluatorType evaluatorType) {

        var streamConfig = config.getStreams().stream()
                .filter(stream -> evaluatorType.name().equalsIgnoreCase(stream.getScorer()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No stream configuration found for evaluator type: " + evaluatorType.name()));

        return new OnlineScoringStreamConfigurationAdapter(config, evaluatorType, streamConfig);
    }

    @Override
    @JsonIgnore
    public Codec getCodec() {
        var scoringCodecs = OnlineScoringCodecs.fromString(streamConfig.getCodec());
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
    public Duration getPendingMessageTimeout() {
        // Use stream-specific value if present, otherwise fall back to global value with default of 10 minutes
        return streamConfig.getPendingMessageTimeout() != null
                ? streamConfig.getPendingMessageTimeout()
                : (onlineScoringConfig.getPendingMessageTimeout() != null
                        ? onlineScoringConfig.getPendingMessageTimeout()
                        : Duration.minutes(10));
    }

    @Override
    public int getClaimIntervalTicks() {
        // Use stream-specific value if present, otherwise fall back to global value with default of 10
        Integer streamValue = streamConfig.getClaimIntervalTicks();
        if (streamValue != null) {
            return streamValue;
        }
        Integer globalValue = onlineScoringConfig.getClaimIntervalTicks();
        return globalValue != null ? globalValue : 10;
    }

    @Override
    public int getMaxRetryAttempts() {
        // Use stream-specific value if present, otherwise fall back to global value with default of 3
        Integer streamValue = streamConfig.getMaxRetryAttempts();
        if (streamValue != null) {
            return streamValue;
        }
        Integer globalValue = onlineScoringConfig.getMaxRetryAttempts();
        return globalValue != null ? globalValue : 3;
    }
}
