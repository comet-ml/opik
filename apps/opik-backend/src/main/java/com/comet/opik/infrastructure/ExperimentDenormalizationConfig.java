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

import java.util.concurrent.TimeUnit;

@Data
public class ExperimentDenormalizationConfig implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";
    public static final String PENDING_SET_KEY = "experiment:denorm:pending";

    @JsonProperty
    private boolean enabled = false;

    @Valid @NotBlank @JsonProperty
    private String streamName = "experiment_denormalization_stream";

    @Valid @NotBlank @JsonProperty
    private String consumerGroupName = "experiment_denormalization";

    @Valid @JsonProperty
    @Min(1) @Max(500) private int consumerBatchSize = 100;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration poolingInterval = Duration.milliseconds(500);

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
    private Duration longPollingDuration = Duration.seconds(5);

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration debounceDelay = Duration.minutes(1);

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration jobLockTime = Duration.seconds(4);

    @Valid @JsonProperty
    @NotNull @MaxDuration(value = 1, unit = TimeUnit.SECONDS)
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration jobLockWaitTime = Duration.milliseconds(300);

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration aggregationLockTime = Duration.minutes(1);

    @JsonProperty
    @Min(1000) @Max(10_000_000) private int streamMaxLen = 100_000;

    @JsonProperty
    @Min(0) @Max(10_000) private int streamTrimLimit = 1000;

    @JsonProperty
    @Min(2) private int claimIntervalRatio = 10;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration pendingMessageDuration = Duration.minutes(10);

    @JsonProperty
    @Min(1) @Max(10) private int maxRetries = 3;

    @Override
    @JsonIgnore
    public Codec getCodec() {
        return RedisStreamCodec.JAVA.getCodec();
    }
}
