package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.SpanWeeksBackfillService;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.comet.opik.infrastructure.SpanWeeksBackfillConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Runs one step of the span weeks backfill per tick (see {@link SpanWeeksBackfillService}). A distributed lock ensures
 * only one instance runs a step at a time, so two instances never plan or backfill the same chunk at once. The lock is
 * held for a whole interval and not released when the step ends, so the cluster runs at most one chunk per interval
 * however many instances tick: a steady load on ClickHouse. The interval exceeds the query timeout (validated), so a
 * step always ends inside its lease.
 * <p>
 * The step runs asynchronously: the tick subscribes and returns, so Quartz threads are never blocked, and
 * {@link #interrupt()} disposes the running step.
 */
@Singleton
@Slf4j
@DisallowConcurrentExecution
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SpanWeeksBackfillJob extends Job implements InterruptableJob {

    private static final Lock RUN_LOCK = new Lock("span_weeks_backfill:run_lock");

    private final @NonNull SpanWeeksBackfillService backfillService;
    private final @NonNull LockService lockService;
    private final @NonNull @Config("spanWeeksBackfill") SpanWeeksBackfillConfig config;
    private final @NonNull @Config("databaseAnalyticsDataModel") DatabaseAnalyticsDataModelConfig dataModelConfig;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Disposable> currentExecution = new AtomicReference<>();

    @Override
    public void doJob(JobExecutionContext context) {
        if (!dataModelConfig.spanWeeksBackfillEnabled() || interrupted.get()) {
            return;
        }
        // Once the backfill is complete cluster-wide, stop before taking the lock: one small MySQL read per tick, no
        // Redis lock and no ClickHouse call.
        var subscription = backfillService.isCompleted()
                .filter(completed -> !completed)
                .flatMap(__ -> lockService.bestEffortLock(
                        RUN_LOCK,
                        Mono.defer(() -> interrupted.get() ? Mono.empty() : backfillService.runStep()),
                        Mono.fromRunnable(() -> log.debug("Span weeks backfill: another instance holds the lock")),
                        config.interval().toJavaDuration(),
                        config.lockWaitTime().toJavaDuration(),
                        true)) // holdUntilExpiry: at most one chunk per interval across the cluster
                .subscribe(
                        null,
                        error -> {
                            log.error("Span weeks backfill step failed, retrying on the next tick", error);
                            currentExecution.set(null);
                        },
                        () -> currentExecution.set(null));
        currentExecution.set(subscription);
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        var execution = currentExecution.get();
        if (execution != null && !execution.isDisposed()) {
            execution.dispose();
        }
        log.info("Span weeks backfill job interrupted");
    }
}
