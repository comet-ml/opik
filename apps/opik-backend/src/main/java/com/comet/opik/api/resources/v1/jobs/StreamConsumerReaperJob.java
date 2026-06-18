package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.resources.v1.events.BaseRedisSubscriber;
import com.comet.opik.infrastructure.StreamConsumerReaperConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.redis.StreamConsumerReaper;
import com.google.inject.Injector;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Periodically removes orphaned Redis stream consumers leaked by non-graceful pod exits (OPIK-6982).
 * <p>
 * The streams to clean are discovered from the registered {@link BaseRedisSubscriber} beans (no keyspace scan, no
 * hand-maintained list) — mirroring how {@code EventListenerRegistrar} discovers event listeners. The actual
 * reaping is delegated to {@link StreamConsumerReaper}. A distributed lock with hold-until-expiry ensures only one
 * instance reaps per cycle, so the fleet does not redundantly re-scan the same groups.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class StreamConsumerReaperJob extends Job implements InterruptableJob {

    private static final Lock JOB_LOCK = new Lock("stream_consumer_reaper:lock");

    private final Injector injector;
    private final StreamConsumerReaper reaper;
    private final LockService lockService;
    private final StreamConsumerReaperConfig config;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    // Subscribers are fixed after startup, so the discovered stream names are computed once and cached.
    private final AtomicReference<List<String>> streamNamesCache = new AtomicReference<>();

    @Inject
    public StreamConsumerReaperJob(@NonNull Injector injector,
            @NonNull StreamConsumerReaper reaper,
            @NonNull LockService lockService,
            @NonNull StreamConsumerReaperConfig config) {
        this.injector = injector;
        this.reaper = reaper;
        this.lockService = lockService;
        this.config = config;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (interrupted.get()) {
            log.info("Stream consumer reaper job interrupted before execution, skipping");
            return;
        }

        List<String> streamNames = streamNamesCache.updateAndGet(
                cached -> cached != null ? cached : discoverStreamNames());

        if (streamNames.isEmpty()) {
            log.debug("No stream subscribers discovered, skipping stream consumer reaper");
            return;
        }

        lockService.bestEffortLock(
                JOB_LOCK,
                reaper.reap(streamNames, config.getIdleThreshold().toJavaDuration())
                        .doOnSuccess(count -> {
                            if (count > 0) {
                                log.info(
                                        "Stream consumer reaper removed '{}' orphaned consumer(s) across '{}' stream(s)",
                                        count, streamNames.size());
                            } else {
                                log.debug("Stream consumer reaper found no orphaned consumers across '{}' stream(s)",
                                        streamNames.size());
                            }
                        }),
                Mono.fromRunnable(() -> log.info(
                        "Could not acquire lock for stream consumer reaper, another instance is running")),
                config.getLockDuration().toJavaDuration(),
                Duration.ZERO,
                true) // holdUntilExpiry: prevent redundant runs across instances until the next cycle
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Stream consumer reaper failed", error));
    }

    /**
     * Discovers the stream names of every registered {@link BaseRedisSubscriber} by scanning the Guice bindings,
     * so new subscribers are covered automatically without registration. Mirrors {@code EventListenerRegistrar}.
     * Package-private for testing against the real application Guice context.
     */
    List<String> discoverStreamNames() {
        List<String> streamNames = injector.getAllBindings().keySet().stream()
                .map(key -> key.getTypeLiteral().getRawType())
                .filter(BaseRedisSubscriber.class::isAssignableFrom)
                .filter(type -> !Modifier.isAbstract(type.getModifiers()))
                .distinct()
                .map(type -> (BaseRedisSubscriber) injector.getInstance(type))
                .map(BaseRedisSubscriber::getStreamName)
                .distinct()
                .toList();
        log.info("Discovered '{}' stream(s) to reap orphaned consumers from: '{}'", streamNames.size(), streamNames);
        return streamNames;
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("Stream consumer reaper job interrupted");
    }
}
