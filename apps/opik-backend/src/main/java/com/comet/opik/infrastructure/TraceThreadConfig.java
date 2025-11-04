package com.comet.opik.infrastructure;

import com.comet.opik.utils.JsonUtils;
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
import org.redisson.codec.CompositeCodec;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.codec.LZ4CodecV2;

import java.util.concurrent.TimeUnit;

@Data
public class TraceThreadConfig implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";

    private static final CompositeCodec CODEC = new CompositeCodec(new LZ4CodecV2(),
            new JsonJacksonCodec(JsonUtils.getMapper()));

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
    @Min(2) private int claimIntervalRatio;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration pendingMessageDuration;

    @JsonProperty
    @Min(1) @Max(10) private int maxRetries;

    @Override
    @JsonIgnore
    public Codec getCodec() {
        return CODEC;
    }
}
