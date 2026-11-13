package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.redis.RedisStreamCodec;
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

/**
 * The stream that carries non-score automation events (a prompt committed, an experiment finished, a CI
 * run). Mechanics only; whether automations run at all is {@code serviceToggles.automationsEnabled}.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AutomationEventsConfig implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";

    @Valid @NotBlank @JsonProperty
    private String streamName;

    @Valid @NotBlank @JsonProperty
    private String consumerGroupName;

    // A message is one firing and processing it may start an experiment or call a webhook, so this stays
    // in the 5-10 range the other external-call consumers use.
    @Valid @JsonProperty
    @Min(1) @Max(100) private int consumerBatchSize;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 10, unit = TimeUnit.SECONDS)
    private Duration poolingInterval;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
    private Duration longPollingDuration;

    @Valid @JsonProperty
    @Min(1) @Max(10) private int maxRetries;

    @Valid @JsonProperty
    @Min(2) @Max(100) private int claimIntervalRatio;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    @MaxDuration(value = 1, unit = TimeUnit.HOURS)
    private Duration pendingMessageDuration;

    @Valid @JsonProperty
    @Min(1000) @Max(10_000_000) private int streamMaxLen;

    @Valid @JsonProperty
    @Min(1) @Max(100_000) private int streamTrimLimit;

    // How often counted triggers (suite grew, queue submitted) and parked runs are checked.
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    @MaxDuration(value = 1, unit = TimeUnit.HOURS)
    private Duration pollInterval = Duration.seconds(30);

    // A run parked on an experiment or an optimization that never finishes fails after this long.
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    @MaxDuration(value = 7, unit = TimeUnit.DAYS)
    private Duration waitTimeout = Duration.hours(24);

    // Where github.check posts commit statuses; a test points it at a stub.
    @Valid @NotBlank @JsonProperty
    private String githubApiUrl = "https://api.github.com";

    @Override
    public Codec getCodec() {
        return RedisStreamCodec.JAVA.getCodec();
    }
}
