package com.comet.opik.domain;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.experimental.UtilityClass;

/**
 * Instruments for annotation queue routing (OPIK-6303).
 *
 * <p>These two counters exist because the failure they describe is otherwise invisible. A score event
 * names the entities whose scores just changed, so finding no scores for one of them means the read saw
 * less than the write produced — ClickHouse replication lag, or a score routed to the assertion-results
 * table instead. Nothing else in the system would report that.
 */
@UtilityClass
public class AnnotationQueueRoutingMetrics {

    public static final String METER_NAME = "opik.annotation_queue_routing";

    private static final Meter METER = GlobalOpenTelemetry.get().getMeter(METER_NAME);

    public static final LongCounter STALE_READS = METER
            .counterBuilder("stale_reads_total")
            .setDescription("Score reads that returned nothing for at least one entity the event named, "
                    + "triggering a delayed re-read")
            .build();

    public static final LongCounter UNRESOLVED_ENTITIES = METER
            .counterBuilder("unresolved_entities_total")
            .setDescription("Entities still without scores after the re-read; either a score that never "
                    + "lands in feedback_scores, or replication lag beyond the retry delay")
            .build();

    public static final LongCounter ITEMS_ROUTED = METER
            .counterBuilder("items_routed_total")
            .setDescription("Items added to annotation queues by automation")
            .build();
}
