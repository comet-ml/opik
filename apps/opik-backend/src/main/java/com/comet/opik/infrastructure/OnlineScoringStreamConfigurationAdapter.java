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
        return onlineScoringConfig.getConsumerBatchSize();
    }

    @Override
    public String getConsumerGroupName() {
        return onlineScoringConfig.getConsumerGroupName();
    }

    @Override
    public Duration getPoolingInterval() {
        return onlineScoringConfig.getPoolingInterval();
    }
}
