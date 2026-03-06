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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.redisson.client.codec.Codec;

import java.util.concurrent.TimeUnit;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TraceThreadConfig implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";

    @Valid @JsonProperty
    private boolean enabled = true;

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
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration timeoutToMarkThreadAsInactive;

    @Valid @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration closeTraceThreadJobInterval;

    @Valid @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration closeTraceThreadJobLockTime;

    @Valid @JsonProperty
    @MaxDuration(value = 1, unit = TimeUnit.SECONDS)
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration closeTraceThreadJobLockWaitTime;

    @Valid @JsonProperty
    @Min(1) @Max(10_000) private int closeTraceThreadMaxItemPerRun;

    @JsonProperty
    @Min(1000) @Max(10_000_000) private int streamMaxLen;

    @JsonProperty
    @Min(0) @Max(10_000) private int streamTrimLimit;

    @JsonProperty
    @Min(2) private int claimIntervalRatio;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration pendingMessageDuration;

    @JsonProperty
    @Min(1) @Max(10) private int maxRetries;

    // lazy codec creation to ensure it picks up the configured JsonUtils mapper
    @Override
    @JsonIgnore
    public Codec getCodec() {
        return RedisStreamCodec.JAVA.getCodec();
    }
}
