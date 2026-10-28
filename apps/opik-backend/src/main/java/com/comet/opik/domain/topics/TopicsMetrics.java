package com.comet.opik.domain.topics;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.time.Duration;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/** The nightly job's metrics. */
@Singleton
public class TopicsMetrics {

    static final String DECISION_SKIP = "skip";
    static final String DECISION_FIRST_BUILD = "first_build";
    static final String DECISION_NOT_REACHED = "not_reached";

    private final LongCounter projects;
    private final LongCounter runs;
    private final DoubleHistogram runDuration;
    private final LongCounter qwenCalls;
    private final LongCounter saveRows;
    private final DoubleHistogram budgetUsed;

    @Inject
    public TopicsMetrics() {
        this(GlobalOpenTelemetry.get().getMeter("opik.topics"));
    }

    TopicsMetrics(@NonNull Meter meter) {
        this.projects = meter.counterBuilder("topics.nightly.projects")
                .setDescription("Projects the nightly job looked at, by decision: skip (under minVectors, or a list "
                        + "already built), first_build, nightly_update, not_reached (the night's budget ran out)")
                .build();
        this.runs = meter.counterBuilder("topics.runs")
                .setDescription("Finished runs, by status (saved, failed) and error code")
                .build();
        this.runDuration = meter.histogramBuilder("topics.run.duration_seconds").setUnit("s")
                .setDescription("Duration of each phase of a run: window, kmeans, qwen, assign, save")
                .build();
        this.qwenCalls = meter.counterBuilder("topics.qwen.calls")
                .setDescription("Qwen calls, by outcome: ok, timeout, error, bad_answer")
                .build();
        this.saveRows = meter.counterBuilder("topics.save.rows")
                .setDescription("Rows a saved run wrote, by table")
                .build();
        this.budgetUsed = meter.histogramBuilder("topics.nightly.budget_used_seconds").setUnit("s")
                .setDescription("Wall time of the night, from its start to its last run's end")
                .build();
    }

    void project(String decision, long count) {
        if (count > 0) {
            projects.add(count, Attributes.of(stringKey("decision"), decision));
        }
    }

    void run(TopicRun.Status status, String errorCode) {
        runs.add(1, errorCode == null
                ? Attributes.of(stringKey("status"), status.getValue())
                : Attributes.of(stringKey("status"), status.getValue(), stringKey("error_code"), errorCode));
    }

    void phase(String phase, Duration duration) {
        runDuration.record(duration.toNanos() / 1e9, Attributes.of(stringKey("phase"), phase));
    }

    void qwen(String outcome) {
        qwenCalls.add(1, Attributes.of(stringKey("outcome"), outcome));
    }

    void saved(String table, long rows) {
        saveRows.add(rows, Attributes.of(stringKey("table"), table));
    }

    void budgetUsed(Duration duration) {
        budgetUsed.record(duration.toNanos() / 1e9);
    }
}
