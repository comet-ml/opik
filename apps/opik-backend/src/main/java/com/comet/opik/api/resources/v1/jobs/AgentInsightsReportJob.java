package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.AgentInsightsJobService;
import com.comet.opik.domain.AgentInsightsReportPublisher;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.annotations.VisibleForTesting;
import io.dropwizard.jobs.Job;
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
                true).subscribe(
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
                                    .then()
                                    .onErrorResume(e -> {
                                        // Per-job isolation: a failed enqueue must not skip the rest.
                                        log.error("Failed to enqueue Agent Insights run for project '{}'",
                                                job.projectId(), e);
                                        return Mono.empty();
                                    }))
                            .then();
                });
    }
}
