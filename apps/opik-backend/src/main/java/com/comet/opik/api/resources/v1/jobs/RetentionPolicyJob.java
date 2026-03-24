package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.retention.RetentionPolicyService;
import com.comet.opik.infrastructure.RetentionConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.lifecycle.Managed;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Slf4j
@EagerSingleton
public class RetentionPolicyJob implements Managed {

    private static final Lock RUN_LOCK = new Lock("retention_policy:run_lock");

    private final RetentionPolicyService retentionPolicyService;
    private final LockService lockService;
    private final RetentionConfig config;

    private volatile Disposable subscription;
    private volatile Scheduler timerScheduler;

    @Inject
    public RetentionPolicyJob(
            @NonNull RetentionPolicyService retentionPolicyService,
            @NonNull LockService lockService,
            @NonNull @Config("retention") RetentionConfig config) {
        this.retentionPolicyService = retentionPolicyService;
        this.lockService = lockService;
        this.config = config;
    }

    @Override
    public void start() {
        if (!config.isEnabled()) {
            log.info("Retention policy job is disabled");
            return;
        }

        if (timerScheduler == null) {
            timerScheduler = Schedulers.newSingle("retention-policy-timer", true);
        }

        if (subscription == null) {
            Duration interval = config.getInterval();
            subscription = Flux.interval(interval, timerScheduler)
                    .onBackpressureDrop(tick -> log.debug("Retention policy backpressure drop, tick '{}'", tick))
                    .concatMap(tick -> executeTick()
                            .onErrorResume(error -> {
                                log.warn("Retention policy tick failed, will retry next interval", error);
                                return Mono.empty();
                            }))
                    .subscribe();

            log.info("Retention policy job started: interval={}, executionsPerDay={}, fractions={}",
                    interval, config.getExecutionsPerDay(), config.getTotalFractions());
        }
    }

    @Override
    public void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            log.info("Retention policy job stopped");
        }
        if (timerScheduler != null && !timerScheduler.isDisposed()) {
            timerScheduler.dispose();
        }
    }

    private Mono<Void> executeTick() {
        Instant now = Instant.now();
        int fraction = computeCurrentFraction(now);

        return lockService.lockUsingToken(RUN_LOCK, Duration.ofSeconds(config.getLockTimeoutSeconds()))
                .flatMap(acquired -> {
                    if (!acquired) {
                        log.info("Retention policy: could not acquire lock, another instance is running");
                        return Mono.empty();
                    }

                    return retentionPolicyService.executeRetentionCycle(fraction, now)
                            .doFinally(__ -> lockService.unlockUsingToken(RUN_LOCK).subscribe());
                });
    }

    int computeCurrentFraction(Instant now) {
        int minuteOfDay = now.atZone(ZoneOffset.UTC).getHour() * 60
                + now.atZone(ZoneOffset.UTC).getMinute();
        int intervalMinutes = (int) config.getInterval().toMinutes();
        return (minuteOfDay / intervalMinutes) % config.getTotalFractions();
    }
}
