package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.SpanWeeksBackfillService;
import com.comet.opik.infrastructure.SpanWeeksBackfillConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Runs one step of the span weeks backfill per tick (see {@link SpanWeeksBackfillService}). A distributed lock keeps
 * a single instance at it, so two instances never plan or backfill the same chunk at once.
 */
@Singleton
@Slf4j
@DisallowConcurrentExecution
public class SpanWeeksBackfillJob extends Job implements InterruptableJob {

    private static final Lock RUN_LOCK = new Lock("span_weeks_backfill:run_lock");

    private final SpanWeeksBackfillService backfillService;
    private final LockService lockService;
    private final SpanWeeksBackfillConfig config;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    @Inject
    public SpanWeeksBackfillJob(
            @NonNull SpanWeeksBackfillService backfillService,
            @NonNull LockService lockService,
            @NonNull @Config("spanWeeksBackfill") SpanWeeksBackfillConfig config) {
        this.backfillService = backfillService;
        this.lockService = lockService;
        this.config = config;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (interrupted.get()) {
            log.info("Span weeks backfill job interrupted before execution, skipping");
            return;
        }
        try {
            lockService.bestEffortLock(
                    RUN_LOCK,
                    backfillService.runStep(),
                    Mono.fromRunnable(() -> log.debug("Span weeks backfill: another instance holds the lock")),
                    config.getQueryTimeout().toJavaDuration(),
                    Duration.ZERO)
                    .block();
        } catch (Exception exception) {
            log.error("Span weeks backfill step failed, retrying on the next tick", exception);
        }
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("Span weeks backfill job interrupted");
    }
}
