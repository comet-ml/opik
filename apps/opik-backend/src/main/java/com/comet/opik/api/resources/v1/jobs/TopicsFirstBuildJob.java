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
 * The topics first-build job, every {@code topics.firstBuild.interval}: builds the list of a project that has none yet
 * during the day, instead of leaving it to the next night. Every pod fires it; the first to take its lock runs it and
 * releases the lock when the run ends, so two runs never overlap. The lease
 * ({@link TopicsNightlyService#firstBuildLease()}) only matters when the pod dies mid-run. The run itself is
 * {@link TopicsNightlyService#runFirstBuilds(Instant)}.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsFirstBuildJob extends Job {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicsNightlyService nightlyService;
    private final @NonNull LockService lockService;

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.isEnabled() || !config.getNightly().isEnabled() || !config.getFirstBuild().isEnabled()) {
            return;
        }
        var start = Instant.now();
        lockService.bestEffortLock(
                TopicsNightlyService.FIRST_BUILD_LOCK,
                Mono.fromRunnable(() -> nightlyService.runFirstBuilds(start)).subscribeOn(Schedulers.boundedElastic()),
                Mono.fromRunnable(() -> log.info("Topics first builds skipped, lock '{}' is held by another instance",
                        TopicsNightlyService.FIRST_BUILD_LOCK.key())),
                nightlyService.firstBuildLease(),
                Duration.ZERO)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Topics first builds failed", error));
    }
}
