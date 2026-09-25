package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.AgentInsightsJobService;
import com.comet.opik.domain.AgentInsightsMetrics;
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
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Runs the first diagnostic for projects enrolled in the auto-first-run rollout (OPIK-7988) once they pass
 * the trace threshold. Enrolment is a row in {@code agent_insights_jobs} with {@code auto_first_run_enrolled}
 * set; {@code auto_first_run_at} being null is what marks one as still owed its run, so no status transition
 * is involved and enrolment stays visible afterwards for analysis.
 * <p>
 * Scheduled programmatically (OpikGuiceyLifecycleEventListener) from
 * {@code agentInsightsReport.autoFirstRunSchedule}, far more often than the daily report sweep so a project
 * crossing the threshold sees results quickly. Its own lock keeps one replica running it at a time.
 * <p>
 * {@code autoFirstRunMaxPerRun} is the only throttle on concurrent diagnostics anywhere in the pipeline:
 * the Redis consumer acks once the platform accepts the trigger, the platform dispatches on a pool and
 * returns, and the pod runs the analysis as a detached task. So the cap sets the arrival rate, and concurrent
 * runs settle at roughly that rate multiplied by how long a run takes.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AgentInsightsAutoFirstRunJob extends Job {

    private static final Lock JOB_LOCK = new Lock("agent_insights_auto_first_run_job:lock");
    @VisibleForTesting
    public static final int MIN_TRACES = 100;
    @VisibleForTesting
    static final Duration WINDOW = Duration.ofDays(7);

    private final @NonNull AgentInsightsJobService agentInsightsJobService;
    private final @NonNull TraceService traceService;
    private final @NonNull LockService lockService;
    private final @NonNull OpikConfiguration config;

    @Override
    public void doJob(JobExecutionContext context) {
        var reportConfig = config.getAgentInsightsReport();

        Mono<Void> timedSweep = Mono.defer(() -> {
            long startMillis = System.currentTimeMillis();
            return runSweep(Instant.now(), reportConfig.getAutoFirstRunMaxPerRun())
                    .doFinally(signalType -> AgentInsightsMetrics.AUTO_FIRST_RUN_DURATION_MS.record(
                            System.currentTimeMillis() - startMillis,
                            signalType == SignalType.ON_COMPLETE
                                    ? AgentInsightsMetrics.OUTCOME_SUCCESS
                                    : AgentInsightsMetrics.OUTCOME_FAILURE));
        });

        lockService.bestEffortLock(
                JOB_LOCK,
                timedSweep,
                Mono.defer(() -> {
                    log.debug("Could not acquire lock for Agent Insights auto-first-run job, another instance is "
                            + "running");
                    return Mono.empty();
                }),
                reportConfig.getJobTimeout().toJavaDuration(),
                reportConfig.getLockWaitTime().toJavaDuration(),
                true)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Agent Insights auto-first-run job failed", error));
    }

    // Candidates come from MySQL — the enrolled projects that have not run yet — so the trace count is a
    // pair-scoped query over that small set rather than a scan of every workspace. Capped per run so a large
    // enrolment still trickles onto the shared trigger queue.
    // The window ends at the passed instant rather than an aligned day boundary, so the threshold is counted
    // over the same rolling window the UI shows the user.
    @VisibleForTesting
    public Mono<Void> runSweep(Instant periodEnd, int maxPerRun) {
        Instant windowStart = periodEnd.minus(WINDOW);

        return Mono.fromCallable(agentInsightsJobService::findAwaitingFirstRun)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(awaiting -> {
                    if (awaiting.isEmpty()) {
                        return Mono.empty();
                    }
                    var pairs = awaiting.stream()
                            .map(job -> Pair.of(job.workspaceId(), job.projectId()))
                            .toList();
                    return traceService.getProjectsWithMinTracesInRange(pairs, windowStart, periodEnd, MIN_TRACES)
                            .flatMapMany(overThreshold -> Flux.fromIterable(awaiting)
                                    .filter(job -> overThreshold.contains(job.projectId())))
                            .take(maxPerRun)
                            .concatMap(job -> Mono
                                    .fromRunnable(() -> agentInsightsJobService.autoFirstRun(
                                            job.workspaceId(), job.projectId(), windowStart, periodEnd))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .then()
                                    .onErrorResume(e -> {
                                        // Per-project isolation, as in the daily sweep: one failed onboarding
                                        // must not skip the rest.
                                        log.error("Failed to run first Agent Insights diagnostic for project '{}'",
                                                job.projectId(), e);
                                        return Mono.empty();
                                    }))
                            .then();
                });
    }
}
