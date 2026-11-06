package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.topics.TopicsNightlyService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;

/**
 * The topics nightly job, on {@code topics.nightly.schedule} (UTC). Every pod fires it; the first to take the night lock
 * runs the night and holds the lock until it expires ({@link TopicsNightlyService#lease()}), so no other pod runs the
 * same night. The night itself is {@link TopicsNightlyService#runNight(Instant)}.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsNightlyJob extends Job {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicsNightlyService nightlyService;
    private final @NonNull LockService lockService;

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.isEnabled() || !config.getNightly().isEnabled()) {
            return;
        }
        var start = Instant.now();
        lockService.bestEffortLock(
                TopicsNightlyService.NIGHT_LOCK,
                Mono.fromRunnable(() -> nightlyService.runNight(start)).subscribeOn(Schedulers.boundedElastic()),
                Mono.fromRunnable(() -> log.info("Topics night skipped, lock '{}' is held by another instance",
                        TopicsNightlyService.NIGHT_LOCK.key())),
                nightlyService.lease(),
                Duration.ZERO,
                true)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Topics night failed", error));
    }
}
