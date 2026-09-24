package com.comet.opik.domain;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.experimental.UtilityClass;

/**
 * Instruments for annotation queue routing (OPIK-6303). Each counter reports something no other signal in
 * the system would: how much automation routes, what it refuses, and where it fails.
 */
@UtilityClass
public class AnnotationQueueRoutingMetrics {

    public static final String METER_NAME = "opik.annotation_queue_routing";

    private static final Meter METER = GlobalOpenTelemetry.get().getMeter(METER_NAME);

    public static final LongCounter ITEMS_ROUTED = METER
            .counterBuilder("items_routed_total")
            .setDescription("Items added to annotation queues by automation")
            .build();

    public static final LongCounter QUEUE_WRITE_FAILURES = METER
            .counterBuilder("queue_write_failures_total")
            .setDescription("Queues whose addItems call failed; the message is left pending so autoClaim "
                    + "retries it, and this counts how often that happens")
            .build();

    public static final LongCounter SCORES_DEDUPLICATED = METER
            .counterBuilder("scores_deduplicated_total")
            .setDescription("Score writes that found their entity already waiting in the Redis buffer and "
                    + "folded into it - the evaluations saved")
            .build();

    public static final LongCounter MESSAGES_FLUSHED = METER
            .counterBuilder("messages_flushed_total")
            .setDescription("Stream messages published by the buffer flush, one per (workspace, scope) batch "
                    + "of due entities")
            .build();

    public static final LongCounter NON_PRODUCTION_SKIPPED = METER
            .counterBuilder("non_production_skipped_total")
            .setDescription("Scored entities dropped before evaluation because they were not logged by an "
                    + "SDK — playground, experiment, optimization or evaluator activity")
            .build();
}
