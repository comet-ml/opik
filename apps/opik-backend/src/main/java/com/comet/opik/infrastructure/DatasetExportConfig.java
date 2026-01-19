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
public class DatasetExportConfig implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";

    @Valid @JsonProperty
    private boolean enabled = true;

    @Valid @NotBlank @JsonProperty
    private String streamName;

    @Valid @NotBlank @JsonProperty
    private String consumerGroupName;

    @Valid @JsonProperty
    @Min(1) @Max(100) private int consumerBatchSize = 10;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration poolingInterval = Duration.seconds(1);

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
    private Duration longPollingDuration = Duration.seconds(5);

    @JsonProperty
    @Min(1) @Max(10) private int maxRetries = 3;

    @JsonProperty
    @Min(2) private int claimIntervalRatio = 2;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration pendingMessageDuration = Duration.minutes(5);

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.HOURS)
    @MaxDuration(value = 7, unit = TimeUnit.DAYS)
    private Duration defaultTtl = Duration.hours(24);

    /**
     * Minimum part size for S3 multipart upload (in bytes).
     * S3 requires minimum 5MB for all parts except the last one.
     * Default: 5MB (5 * 1024 * 1024 = 5242880 bytes)
     */
    @JsonProperty
    @Min(5 * 1024 * 1024) // S3 minimum is 5MB
    private int minPartSize = 5 * 1024 * 1024;

    /**
     * Maximum part size for S3 multipart upload (in bytes).
     * S3 allows up to 5GB per part, but we limit to avoid memory issues.
     * Default: 10MB (10 * 1024 * 1024 = 10485760 bytes)
     */
    @JsonProperty
    @Min(5 * 1024 * 1024) @Max(500 * 1024 * 1024) // Cap at 500MB to avoid memory issues
    private int maxPartSize = 10 * 1024 * 1024;

    /**
     * Number of items to fetch per batch when streaming dataset items.
     * Default: 1000
     */
    @JsonProperty
    @Min(100) @Max(10000) private int itemBatchSize = 100;

    /**
     * Timeout for cleanup job execution (how long the cleanup can run).
     * Default: 5 minutes
     */
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    @MaxDuration(value = 30, unit = TimeUnit.MINUTES)
    private Duration cleanupTimeout = Duration.minutes(5);

    /**
     * How long to wait for acquiring the distributed lock for cleanup.
     * Default: 1 second
     */
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 10, unit = TimeUnit.SECONDS)
    private Duration cleanupLockWaitTime = Duration.seconds(1);

    /**
     * Batch size for cleanup job operations (number of jobs to process per batch).
     * Default: 100
     */
    @JsonProperty
    @Min(10) @Max(1000) private int cleanupBatchSize = 100;

    // lazy codec creation to ensure it picks up the configured JsonUtils mapper
    @Override
    @JsonIgnore
    public Codec getCodec() {
        return RedisStreamCodec.JAVA.getCodec();
    }
}
