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
 * consumer. The stream is what makes that work survive a replica restart — there is no backfill or manual
 * re-run to recover a dropped event, so an in-memory handoff would lose a trace from a review queue
 * permanently and silently.
 */
@Data
public class AnnotationQueueRoutingConfig implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";

    @Valid @JsonProperty
    private boolean enabled = true;

    @Valid @NotBlank @JsonProperty
    private String streamName = "annotation-queue-routing";

    /**
     * How long an entity waits after its first score before it is evaluated. Later scores on the same
     * entity land inside the window and collapse into the same evaluation instead of repeating it.
     */
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 500, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 5, unit = TimeUnit.MINUTES)
    private Duration debounceDelay = Duration.seconds(5);

    /** How often the flush job looks for entities whose window has elapsed. */
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 500, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration jobInterval = Duration.seconds(2);

    /** Entities taken per flush; also the cap on how many entity ids one published message can carry. */
    @Valid @JsonProperty
    @Min(1) @Max(10000) private int jobBatchSize = 500;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration jobLockTime = Duration.seconds(4);

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration jobLockWaitTime = Duration.milliseconds(300);

    @Valid @NotBlank @JsonProperty
    private String consumerGroupName = "annotation-queue-routing-consumers";

    // Each message fans out to one MySQL read, one ClickHouse read and up to one write per matching queue,
    // so this is the main lever on concurrent database work when scores arrive in bursts.
    @Valid @JsonProperty
    @Min(1) @Max(100) private int consumerBatchSize = 10;

    // These three set the throughput ceiling together, and it is easy to under-provision by accident:
    // new messages per second per replica is roughly
    //     consumerBatchSize / (poolingInterval x claimIntervalRatio)
    // because the read loop is driven by a fixed interval and every claimIntervalRatio-th tick spends its
    // turn on autoClaim, which fetches only already-pending work. At 10 / (500ms x 10) that is ~20/s per
    // replica, against a per-message cost of roughly 20-80ms at concurrency 10 — so the clock, not the
    // work, is the limit. Under-provisioning here is not merely slow: the backlog grows until streamMaxLen
    // trimming starts discarding the oldest messages, which is silent. Matches the onlineScoring tuning.
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration poolingInterval = Duration.milliseconds(500);

    // How long to wait before re-reading scores for entities the event named but the first read found
    // none for. Covers ClickHouse replication lag and the async-insert buffer window
    // (async_insert_busy_timeout_max_ms defaults to 250ms), both of which resolve well inside this.
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 50, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 10, unit = TimeUnit.SECONDS)
    private Duration staleReadRetryDelay = Duration.milliseconds(500);

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
    private Duration longPollingDuration = Duration.seconds(5);

    @JsonProperty
    @Min(1) @Max(10) private int maxRetries = 3;

    @JsonProperty
    @Min(2) private int claimIntervalRatio = 10;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration pendingMessageDuration = Duration.minutes(5);

    @JsonProperty
    @Min(1000) @Max(10_000_000) private int streamMaxLen = 100_000;

    @JsonProperty
    @Min(0) @Max(10_000) private int streamTrimLimit = 1000;

    // Lazy codec creation so it picks up the configured JsonUtils mapper.
    @Override
    @JsonIgnore
    public Codec getCodec() {
        return RedisStreamCodec.JAVA.getCodec();
    }
}
