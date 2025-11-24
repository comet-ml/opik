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
public class WebhookConfig implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";

    @Valid @JsonProperty
    private boolean enabled = false;

    @Valid @NotBlank @JsonProperty
    private String streamName = "webhook-events";

    @Valid @NotBlank @JsonProperty
    private String consumerGroupName = "webhook-consumers";

    @Valid @JsonProperty
    @Min(1) @Max(100) private int consumerBatchSize = 10;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration poolingInterval = Duration.seconds(1);

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
    private Duration longPollingDuration;

    @JsonProperty
    @Min(1) @Max(10) private int maxRetries;

    // Webhook-specific configuration

    @Valid @JsonProperty
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration initialRetryDelay = Duration.milliseconds(500);

    @Valid @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration maxRetryDelay = Duration.seconds(30);

    @Valid @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration requestTimeout = Duration.seconds(10);

    @Valid @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration connectionTimeout = Duration.seconds(5);

    // Debouncing configuration
    @Valid @JsonProperty
    private DebouncingConfig debouncing = new DebouncingConfig();

    // Metrics alert job configuration
    @Valid @JsonProperty
    private MetricsConfig metrics = new MetricsConfig();

    @JsonProperty
    @Min(2) private int claimIntervalRatio;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration pendingMessageDuration;

    // lazy codec creation to ensure it picks up the configured JsonUtils mapper
    @Override
    @JsonIgnore
    public Codec getCodec() {
        return RedisStreamCodec.JAVA.getCodec();
    }

    /**
     * Configuration for webhook event debouncing and aggregation.
     */
    @Data
    public static class DebouncingConfig {

        @Valid @JsonProperty
        private boolean enabled = true;

        @Valid @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration windowSize = Duration.seconds(60);

        @Valid @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration bucketTtl = Duration.minutes(3);

        @Valid @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration alertJobTimeout = Duration.seconds(4);

        @Valid @JsonProperty
        @MaxDuration(value = 500, unit = TimeUnit.MILLISECONDS)
        private Duration alertJobLockWaitTimeout = Duration.milliseconds(100);
    }

    /**
     * Configuration for metrics alert job scheduling.
     */
    @Data
    public static class MetricsConfig {

        @Valid @JsonProperty
        @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration initialDelay = Duration.seconds(300);

        @Valid @JsonProperty
        @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration fixedDelay = Duration.seconds(300);

        @Valid @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration metricsAlertJobTimeout = Duration.seconds(60);

        @Valid @JsonProperty
        @MaxDuration(value = 10, unit = TimeUnit.SECONDS)
        private Duration metricsAlertJobLockWaitTimeout = Duration.seconds(1);
    }
}
