package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.redis.RedisStreamCodec;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.redisson.client.codec.Codec;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Data
public class ExperimentExecutionConfig implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";
    public static final String ITEM_COUNTER_KEY_PREFIX = "opik:experiment:items:";
    public static final String TEST_SUITE_ASSERTION_COUNTER_KEY_PREFIX = "opik:experiment:assertion:";
    public static final String CANCELLED_EXPERIMENT_KEY_PREFIX = "opik:experiment:cancelled:";
    public static final String QUEUED_IDS_KEY_PREFIX = "opik:experiment:queued-ids:";

    /**
     * Outstanding items for one prompt variant of a run. Scoped to the experiment rather than to the
     * run so each variant settles on its own work: one that finishes is not held open by its
     * siblings, and one that is cancelled takes nothing down with it.
     */
    public static String itemCounterKey(UUID experimentId) {
        return ITEM_COUNTER_KEY_PREFIX + experimentId;
    }

    public static String itemFailureCounterKey(UUID experimentId) {
        return itemCounterKey(experimentId) + ":failures";
    }

    @Valid @NotBlank @JsonProperty
    private String defaultProjectName = "playground";

    @JsonProperty
    private boolean enabled = true;

    @Valid @NotBlank @JsonProperty
    private String streamName = "experiment_item_processing_stream";

    @Valid @NotBlank @JsonProperty
    private String consumerGroupName = "experiment_item_processing";

    @Valid @JsonProperty
    @Min(1) @Max(500) private int consumerBatchSize = 10;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration poolingInterval = Duration.milliseconds(500);

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
    private Duration longPollingDuration = Duration.seconds(5);

    @JsonProperty
    @Min(1000) @Max(10_000_000) private int streamMaxLen = 50_000;

    @JsonProperty
    @Min(0) @Max(10_000) private int streamTrimLimit = 100;

    @JsonProperty
    @Min(2) private int claimIntervalRatio = 10;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration pendingMessageDuration = Duration.minutes(10);

    @JsonProperty
    @Min(1) @Max(10) private int maxRetries = 3;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.HOURS)
    private Duration batchCounterTtl = Duration.hours(24);

    @Override
    @JsonIgnore
    public Codec getCodec() {
        return RedisStreamCodec.JAVA.getCodec();
    }
}
