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
    static final String DECISION_NIGHTLY_UPDATE = "nightly_update";
    static final String DECISION_NOT_REACHED = "not_reached";

    private final LongCounter projects;
    private final LongCounter runs;
    private final DoubleHistogram runDuration;
    private final LongCounter qwenCalls;
    private final LongCounter saveRows;
    private final DoubleHistogram budgetUsed;
    private final DoubleHistogram liveTopics;
    private final LongCounter retired;
    private final LongCounter born;
    private final DoubleHistogram churn;
    private final DoubleHistogram churnAny;

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
                .setDescription("Qwen calls, by kind (author, revise, verify, naming) and outcome: ok, timeout, "
                        + "error, bad_answer")
                .build();
        this.saveRows = meter.counterBuilder("topics.save.rows")
                .setDescription("Rows a saved run wrote, by table")
                .build();
        this.budgetUsed = meter.histogramBuilder("topics.nightly.budget_used_seconds").setUnit("s")
                .setDescription("Wall time of the night, from its start to its last run's end")
                .build();
        this.liveTopics = meter.histogramBuilder("topics.topics.live")
                .setDescription("Live topics of a project after each saved run")
                .build();
        this.retired = meter.counterBuilder("topics.topics.retired")
                .setDescription("Topics retired by saved nightly updates, by reason: low_share, cap, merged, review, "
                        + "replaced")
                .build();
        this.born = meter.counterBuilder("topics.topics.born")
                .setDescription("Topics born by saved runs, by mode: first_build, nightly_update")
                .build();
        this.churn = meter.histogramBuilder("topics.run.churn")
                .setDescription("Of the window traces in a topic on both the last and this saved night, the share "
                        + "whose topic changed")
                .build();
        this.churnAny = meter.histogramBuilder("topics.run.churn_any")
                .setDescription("Of the window traces labelled on both the last and this saved night, the share "
                        + "whose label changed, Other included")
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

    void qwen(String kind, String outcome) {
        qwenCalls.add(1, Attributes.of(stringKey("kind"), kind, stringKey("outcome"), outcome));
    }

    void topics(TopicRun.Mode mode, int live, int bornTonight) {
        liveTopics.record(live);
        if (bornTonight > 0) {
            born.add(bornTonight, Attributes.of(stringKey("mode"), mode.getValue()));
        }
    }

    void retired(String reason) {
        retired.add(1, Attributes.of(stringKey("reason"), reason));
    }

    void churn(TopicChurn.Churn value) {
        churn.record(value.churn());
        churnAny.record(value.churnAny());
    }

    void saved(String table, long rows) {
        saveRows.add(rows, Attributes.of(stringKey("table"), table));
    }

    void budgetUsed(Duration duration) {
        budgetUsed.record(duration.toNanos() / 1e9);
    }
}
