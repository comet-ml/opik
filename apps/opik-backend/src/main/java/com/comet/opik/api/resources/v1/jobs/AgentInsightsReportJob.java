package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.AgentInsightsJobService;
import com.comet.opik.domain.AgentInsightsMetrics;
import com.comet.opik.domain.AgentInsightsReportPublisher;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.annotations.VisibleForTesting;
import io.dropwizard.jobs.Job;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Daily cron that triggers Agent Insights report generation for enabled projects that had traces during
 * the previous UTC day. The schedule is configured programmatically (OpikGuiceyLifecycleEventListener)
 * from {@code agentInsightsReport.schedule}, so it is env-configurable. A distributed lock guarantees a
 * single replica runs the sweep per day, so no extra once-per-day guard is needed. Mirrors
 * {@code OllieDailyReportJob} (distributed lock + per-item failure isolation).
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AgentInsightsReportJob extends Job {

    private static final Lock JOB_LOCK = new Lock("agent_insights_report_job:lock");

    private static final Meter METER = GlobalOpenTelemetry.get().getMeter(AgentInsightsMetrics.METER_NAME);

    private final LongHistogram sweepDuration = METER
            .histogramBuilder(AgentInsightsMetrics.SWEEP_DURATION)
            .setDescription(AgentInsightsMetrics.SWEEP_DURATION_DESC)
            .setUnit("ms")
            .ofLongs()
            .build();
    private final LongCounter reportsEnqueued = METER
            .counterBuilder(AgentInsightsMetrics.REPORTS_ENQUEUED)
            .setDescription(AgentInsightsMetrics.REPORTS_ENQUEUED_DESC)
            .build();
    private final LongCounter triggerErrors = METER
            .counterBuilder(AgentInsightsMetrics.TRIGGER_ERRORS)
            .setDescription(AgentInsightsMetrics.TRIGGER_ERRORS_DESC)
            .build();

    private final @NonNull AgentInsightsJobService agentInsightsJobService;
    private final @NonNull TraceService traceService;
    private final @NonNull AgentInsightsReportPublisher reportPublisher;
    private final @NonNull LockService lockService;
    private final @NonNull OpikConfiguration config;

    @Override
    public void doJob(JobExecutionContext context) {
        LocalDate runDate = LocalDate.now(ZoneOffset.UTC);
        Instant periodStart = runDate.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant periodEnd = runDate.atStartOfDay(ZoneOffset.UTC).toInstant();

        log.info("Agent Insights report job running for previous UTC day ['{}', '{}')", periodStart, periodEnd);

        long startMillis = System.currentTimeMillis();
        var reportConfig = config.getAgentInsightsReport();
        // holdUntilExpiry: only one replica runs the sweep per day (idempotent across replicas).
        lockService.bestEffortLock(
                JOB_LOCK,
                runSweep(periodStart, periodEnd),
                Mono.defer(() -> {
                    log.debug("Could not acquire lock for Agent Insights report job, another instance is running");
                    return Mono.empty();
                }),
                reportConfig.getJobTimeout().toJavaDuration(),
                reportConfig.getLockWaitTime().toJavaDuration(),
                true)
                .doFinally(signalType -> sweepDuration.record(System.currentTimeMillis() - startMillis,
                        Attributes.of(AgentInsightsMetrics.OUTCOME,
                                signalType == SignalType.ON_COMPLETE
                                        ? AgentInsightsMetrics.SUCCESS
                                        : AgentInsightsMetrics.FAILURE)))
                .subscribe(
                        __ -> log.info("Agent Insights report job completed"),
                        error -> log.error("Agent Insights report job failed", error));
    }

    // Visible for testing: the sweep does ONE trace query for the whole enabled set (no per-workspace or
    // per-project loop), filters to projects that had traces, and enqueues each (per-job failure isolated).
    @VisibleForTesting
    public Mono<Void> runSweep(Instant periodStart, Instant periodEnd) {
        return Mono.fromCallable(agentInsightsJobService::findAllEnabled)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(jobs -> {
                    if (jobs.isEmpty()) {
                        return Mono.empty();
                    }
                    var pairs = jobs.stream()
                            .map(job -> Pair.of(job.workspaceId(), job.projectId()))
                            .toList();
                    return traceService.getProjectsWithTracesInRange(pairs, periodStart, periodEnd)
                            .flatMapMany(withTraces -> Flux.fromIterable(jobs)
                                    .filter(job -> withTraces.contains(job.projectId())))
                            .concatMap(job -> reportPublisher
                                    .enqueue(job.projectId(), job.workspaceId(), periodStart, periodEnd)
                                    .doOnNext(__ -> reportsEnqueued.add(1,
                                            Attributes.of(AgentInsightsMetrics.TRIGGER,
                                                    AgentInsightsMetrics.SCHEDULED)))
                                    .then()
                                    .onErrorResume(e -> {
                                        // Per-job isolation: a failed enqueue must not skip the rest.
                                        triggerErrors.add(1, Attributes.of(AgentInsightsMetrics.TRIGGER,
                                                AgentInsightsMetrics.SCHEDULED));
                                        log.error("Failed to enqueue Agent Insights run for project '{}'",
                                                job.projectId(), e);
                                        return Mono.empty();
                                    }))
                            .then();
                });
    }
}
