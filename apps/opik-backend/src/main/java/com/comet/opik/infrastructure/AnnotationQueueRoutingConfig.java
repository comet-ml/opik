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

/**
 * Stream carrying annotation queue routing work (OPIK-6303).
 *
 * <p>The event listener only guards and publishes; everything that reads, decides or writes happens in the
 * consumer, which folds a batch by entity before doing any of it, so repeated scores on one entity cost
 * one evaluation rather than one each. The stream is what makes that work survive a replica restart — there is no backfill or manual
 * re-run to recover a dropped event, so an in-memory handoff would lose a trace from a review queue
 * permanently and silently.
 *
 * <p>Values live in {@code config.yml} and its test counterpart, which is the single source of truth:
 * no field carries a Java default, so a key missing from the yaml fails validation at boot rather than
 * silently taking a value that appears nowhere on disk.
 */
@Data
public class AnnotationQueueRoutingConfig implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";

    @Valid @JsonProperty
    private boolean enabled;

    @Valid @NotBlank @JsonProperty
    private String streamName;

    @Valid @NotBlank @JsonProperty
    private String consumerGroupName;

    // Each message fans out to one MySQL read, one ClickHouse read and up to one write per matching queue,
    // so this is the main lever on concurrent database work when scores arrive in bursts.
    @Valid @JsonProperty
    @Min(1) @Max(100) private int consumerBatchSize;

    // These three set the throughput ceiling together, and it is easy to under-provision by accident:
    // new messages per second per replica is roughly
    //     (consumerBatchSize / poolingInterval) x (1 - 1 / claimIntervalRatio)
    // because the read loop is driven by a fixed interval and every claimIntervalRatio-th tick spends its
    // turn on autoClaim, which fetches only already-pending work. At the shipped 10 per tick, one tick a
    // second and nine ticks in ten reading, that is about 9/s per replica, against a per-message cost of
    // roughly 20-80ms at concurrency 10 — so the clock, not the work, is the limit. Under-provisioning
    // here is not merely slow: the backlog grows until streamMaxLen trimming starts discarding the oldest
    // messages, which is silent, and one message per score event now rather than one per debounced group
    // means there are more of them. consumerBatchSize is the lever to raise alongside this.
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 10, unit = TimeUnit.SECONDS)
    private Duration poolingInterval;

    // How long to wait before re-reading scores for entities the event named but the first read found
    // none for. Covers ClickHouse replication lag and the async-insert buffer window
    // (async_insert_busy_timeout_max_ms defaults to 250ms), both of which resolve well inside this.
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 50, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 10, unit = TimeUnit.SECONDS)
    private Duration staleReadRetryDelay;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
    private Duration longPollingDuration;

    @JsonProperty
    @Min(1) @Max(10) private int maxRetries;

    @JsonProperty
    @Min(2) @Max(100) private int claimIntervalRatio;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    @MaxDuration(value = 1, unit = TimeUnit.HOURS)
    private Duration pendingMessageDuration;

    @JsonProperty
    @Min(1000) @Max(10_000_000) private int streamMaxLen;

    @JsonProperty
    @Min(1) @Max(10_000) private int streamTrimLimit;

    // Lazy codec creation so it picks up the configured JsonUtils mapper.
    @Override
    @JsonIgnore
    public Codec getCodec() {
        return RedisStreamCodec.JAVA.getCodec();
    }
}
