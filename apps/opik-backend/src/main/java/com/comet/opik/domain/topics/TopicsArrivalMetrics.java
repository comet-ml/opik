package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.time.Duration;

import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/** Topic on arrival's metrics, tagged with the workspace attributes the vectors tick looked up. */
@Singleton
class TopicsArrivalMetrics {

    private final LongCounter labelled;
    private final LongCounter failures;
    private final DoubleHistogram duration;

    @Inject
    TopicsArrivalMetrics() {
        this(GlobalOpenTelemetry.get().getMeter("opik.topics"));
    }

    TopicsArrivalMetrics(@NonNull Meter meter) {
        this.labelled = meter.counterBuilder("topics.arrival.labelled")
                .setDescription("Traces the vectors tick labelled on arrival, by outcome and workspace: topic or other "
                        + "(a trace_topics row written), no_prototypes (the project has no saved list, or one with "
                        + "no topic), over_budget (no time left of topics.arrival.timeout or of the tick), "
                        + "after_failure (arrival stopped earlier in the tick)")
                .build();
        this.failures = meter.counterBuilder("topics.arrival.failures")
                .setDescription("Arrival steps that failed, by phase: read (the projects' saved lists in MySQL), "
                        + "insert (trace_topics), assign (any other failure of the step); with error type and "
                        + "workspace, TimeoutException when the step ran out of time. Arrival stops for the rest of "
                        + "the tick; the next run labels those traces")
                .build();
        this.duration = meter.histogramBuilder("topics.arrival.duration_seconds").setUnit("s")
                .setDescription("Wall time of the arrival step after one vectors insert, by workspace; steps that "
                        + "do not start are not recorded")
                .build();
    }

    void labelled(@NonNull TopicVectorsMetrics.Workspace workspace, @NonNull ArrivalOutcome outcome, int traces) {
        if (traces > 0) {
            labelled.add(traces, workspace.attributes().toBuilder()
                    .put(stringKey("outcome"), outcome.getValue())
                    .build());
        }
    }

    void failure(@NonNull TopicVectorsMetrics.Workspace workspace, @NonNull ArrivalPhase phase,
            @NonNull Throwable error) {
        failures.add(1, workspace.attributes().toBuilder()
                .put(stringKey("phase"), phase.getValue())
                .put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(error))
                .build());
    }

    void duration(@NonNull TopicVectorsMetrics.Workspace workspace, @NonNull Duration took) {
        duration.record(took.toNanos() / 1e9, workspace.attributes());
    }
}
