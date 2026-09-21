package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.redis.RedisStreamCodec;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.redisson.client.codec.Codec;

/**
 * Stream carrying annotation queue routing work (OPIK-6303).
 *
 * <p>The producer's half only. The event listener guards and publishes; everything that reads, decides or
 * writes happens in the consumer, which is a separate change — and so are its settings (consumer group,
 * batch size, poll and claim intervals, retries, the stale-read delay). They arrive with the code that
 * reads them, so that a reviewer sees each value next to the loop it tunes rather than ahead of it.
 *
 * <p>The stream is what makes that work survive a replica restart — there is no backfill or manual re-run
 * to recover a dropped event, so an in-memory handoff would lose a trace from a review queue permanently
 * and silently. {@code streamMaxLen} and {@code streamTrimLimit} are here because trimming happens at
 * XADD time, on this side.
 *
 * <p>Values live in {@code config.yml} and its test counterpart, which is the single source of truth:
 * no field carries a Java default, so a key missing from the yaml fails validation at boot rather than
 * silently taking a value that appears nowhere on disk.
 */
@Data
public class AnnotationQueueRoutingConfig {

    public static final String PAYLOAD_FIELD = "message";

    @Valid @JsonProperty
    private boolean enabled;

    @Valid @NotBlank @JsonProperty
    private String streamName;

    @JsonProperty
    @Min(1000) @Max(10_000_000) private int streamMaxLen;

    @JsonProperty
    @Min(1) @Max(10_000) private int streamTrimLimit;

    // Lazy codec creation so it picks up the configured JsonUtils mapper.
    @JsonIgnore
    public Codec getCodec() {
        return RedisStreamCodec.JAVA.getCodec();
    }
}
