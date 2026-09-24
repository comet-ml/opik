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

/**
 * Annotation queue routing (OPIK-6303): the Redis buffer score events are collected in, the job that
 * flushes it, and the stream the flushed batches travel on to the consumer.
 *
 * <p>The buffer is a single ZSET keyed by (workspace, scope, entity) and scored by the time of the first
 * write, so a burst of scores on one entity is one member, and nothing is flushed before it has sat there
 * for {@code bufferMinAge}. That floor is also what keeps the consumer's ClickHouse read clear of
 * replication lag. The flush job groups due members by (workspace, scope) and publishes one stream message
 * per group; the consumer then processes one message at a time.
 *
 * <p>Values live in {@code config.yml} and its test counterpart, which is the single source of truth:
 * no field carries a Java default, so a key missing from the yaml fails validation at boot rather than
 * silently taking a value that appears nowhere on disk.
 */
// @NoArgsConstructor and @AllArgsConstructor are back because @Builder removes the implicit no-arg one,
// and Jackson needs it to construct this from the yaml.
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AnnotationQueueRoutingConfig implements StreamConfiguration {

    public static final String PAYLOAD_FIELD = "message";
    public static final String PENDING_SET_KEY = "annotation-queue:routing:pending";

    @Valid @JsonProperty
    private boolean enabled;

    // Off in tests that drive the flush job by hand; the buffer still fills either way.
    @Valid @JsonProperty
    private boolean jobEnabled;

    @Valid @NotBlank @JsonProperty
    private String streamName;

    @Valid @NotBlank @JsonProperty
    private String consumerGroupName;

    // Also the number of messages processed concurrently. A message is one (workspace, scope) batch, and
    // processing it is one MySQL read, two ClickHouse reads and up to one write per matching
    // queue, so this bounds concurrent database work by batch. 100 is what the other database-only
    // consumers use; the 5-10 elsewhere caps slow external calls this consumer does not make.
    @Valid @JsonProperty
    @Min(1) @Max(100) private int consumerBatchSize;

    // How long a member must have been in the buffer before a flush may take it. Every score written to an
    // entity within this window folds into the one member, and the consumer never reads scores younger
    // than this — well past typical ClickHouse replica lag. Latency added to every routed item.
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration bufferMinAge;

    // Bounds the buffer if nothing drains it. Writers set it only when the key has none, and each flush run
    // renews it, so the key outlives the last flush by this much and then expires with whatever is in it.
    // Members are small, but at a high score rate a dead flusher would otherwise grow the key without limit.
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 10, unit = TimeUnit.SECONDS)
    @MaxDuration(value = 1, unit = TimeUnit.HOURS)
    private Duration bufferTtl;

    // Members are due bufferMinAge after their write and are picked up on the next run, so an item is
    // routed between bufferMinAge and bufferMinAge + jobInterval after its last score.
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    @MaxDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration jobInterval;

    // Held until expiry so one replica flushes per cycle; kept below jobInterval so the next cycle is not
    // skipped. Also the time budget of one run: a run cut short leaves the rest for the next one, since
    // members are removed only once their batch is on the stream.
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 500, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration jobLockTime;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 5, unit = TimeUnit.SECONDS)
    private Duration jobLockWaitTime;

    // Members read per page while flushing, and therefore the most entities one stream message can carry.
    @Valid @JsonProperty
    @Min(100) @Max(5000) private int jobBatchSize;

    // These three set the consumer's throughput ceiling together, and it is easy to under-provision by
    // accident: new messages per second per replica is roughly
    //     (consumerBatchSize / poolingInterval) x (1 - 1 / claimIntervalRatio)
    // because the read loop is driven by a fixed interval and every claimIntervalRatio-th tick spends its
    // turn on autoClaim, which fetches only already-pending work. Under-provisioning here is not merely
    // slow: the backlog grows until streamMaxLen trimming starts discarding the oldest messages, which is
    // silent. Raise consumerBatchSize before lowering poolingInterval.
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 10, unit = TimeUnit.SECONDS)
    private Duration poolingInterval;

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
