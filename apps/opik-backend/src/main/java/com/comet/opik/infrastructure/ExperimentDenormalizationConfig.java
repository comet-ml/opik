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
    private String streamName;

    @Valid @NotBlank @JsonProperty
    private String consumerGroupName;

    @Valid @JsonProperty
    @Min(1) private int consumerBatchSize;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration poolingInterval;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
    private Duration longPollingDuration;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration debounceDelay;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration jobLockTime;

    @Valid @JsonProperty
    @MaxDuration(value = 1, unit = TimeUnit.SECONDS)
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration jobLockWaitTime;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration aggregationLockTime;

    @JsonProperty
    @Min(2) private int claimIntervalRatio;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration pendingMessageDuration;

    @JsonProperty
    @Min(1) @Max(10) private int maxRetries;

    @Override
    @JsonIgnore
    public Codec getCodec() {
        return RedisStreamCodec.JAVA.getCodec();
    }
}
