package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.ProjectIdLastUpdated;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.ProjectLastUpdatedFlushConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.UnableToInterruptJobException;
import org.redisson.api.RScoredSortedSetReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.ScoredEntry;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Scheduled job that drains the Redis-buffered {@code projects.last_updated_trace_at} maxima (written on the
 * ingestion path by {@code ProjectLastUpdatedTraceBuffer}) to MySQL via {@link ProjectService#recordLastUpdatedTrace}.
 *
 * <p>Runs periodically (default 30s, {@code projectLastUpdatedFlush.jobInterval}) under a best-effort distributed
 * lock held until expiry, so a single instance flushes per cycle across the cluster. Draining and writing are
 * idempotent (the MySQL write only moves the marker forward), so a lost lock or overlapping run is safe.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class ProjectLastUpdatedFlushJob extends Job implements InterruptableJob {

    private static final Lock FLUSH_LOCK = new Lock("project_last_updated_flush_job:scan_lock");

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final ProjectLastUpdatedFlushConfig config;
    private final RedissonReactiveClient redisClient;
    private final ProjectService projectService;
    private final LockService lockService;

    @Inject
    public ProjectLastUpdatedFlushJob(
            @NonNull @Config("projectLastUpdatedFlush") ProjectLastUpdatedFlushConfig config,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull ProjectService projectService,
            @NonNull LockService lockService) {
        this.config = config;
        this.redisClient = redisClient;
        this.projectService = projectService;
        this.lockService = lockService;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.isEnabled() || interrupted.get()) {
            return;
        }

        lockService.bestEffortLock(
                FLUSH_LOCK,
                Mono.defer(this::flush)
                        .doOnNext(count -> {
                            if (count > 0) {
                                log.info("Project last-updated flush wrote '{}' project markers", count);
                            }
                        })
                        .then(),
                Mono.fromRunnable(() -> log.debug(
                        "Another instance is flushing project last-updated markers, skipping")),
                config.getJobLockTime().toJavaDuration(),
                config.getJobLockWaitTime().toJavaDuration(),
                true)
                .subscribe(
                        __ -> {
                        },
                        error -> log.error("Project last-updated flush job failed", error));
    }

    /**
     * Atomically moves the live buffer aside ({@code renamenx} to the snapshot key) so concurrent buffer writes
     * (addIfGreater) immediately populate a fresh live key, then drains the snapshot. Moving the data aside removes
     * the read-then-remove race — the drain owns the snapshot exclusively. When a snapshot from a previously
     * interrupted flush still exists, {@code renamenx} is a no-op and that leftover is drained instead (idempotent,
     * since the DB write only moves the marker forward).
     */
    private Mono<Long> flush() {
        var pending = pendingSet();
        var flushing = flushingSet();

        return pending.isExists()
                .flatMap(exists -> Boolean.TRUE.equals(exists)
                        ? pending.renamenx(ProjectLastUpdatedFlushConfig.FLUSHING_SET_KEY)
                        : Mono.just(false))
                .then(drainSnapshot(flushing, 0L));
    }

    /**
     * Drains the snapshot one page at a time, reading the next page only after the current page's write and
     * {@code removeAll} complete. Re-reading the lowest range advances because each processed page is removed first;
     * gating the next read on removal (rather than {@code Flux.expand}, which would pre-fetch before removal) prevents
     * the same top entries from being read and written to MySQL repeatedly. Removal guarantees progress.
     */
    private Mono<Long> drainSnapshot(RScoredSortedSetReactive<String> flushing, long writtenSoFar) {
        int batchSize = config.getJobBatchSize();
        return flushing.entryRange(0, batchSize - 1)
                .flatMap(entries -> {
                    if (entries.isEmpty()) {
                        return Mono.just(writtenSoFar);
                    }
                    return flushBatch(flushing, entries)
                            .flatMap(written -> entries.size() < batchSize
                                    ? Mono.just(writtenSoFar + written)
                                    : Mono.defer(() -> drainSnapshot(flushing, writtenSoFar + written)));
                });
    }

    /**
     * Writes one page of snapshot entries, grouped by workspace so each MySQL batch write targets a single
     * {@code workspace_id} (matching the DAO binding), then removes the processed members. Removal is safe because
     * the snapshot is owned exclusively by this drain — no concurrent buffer write can re-bump a member in between.
     */
    private Mono<Long> flushBatch(RScoredSortedSetReactive<String> flushing, Collection<ScoredEntry<String>> entries) {
        var byWorkspace = entries.stream()
                .map(ProjectLastUpdatedFlushJob::parseMember)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        ParsedMember::workspaceId,
                        Collectors.mapping(
                                parsed -> ProjectIdLastUpdated.builder()
                                        .id(parsed.projectId())
                                        .lastUpdatedAt(parsed.lastUpdatedAt())
                                        .build(),
                                Collectors.toUnmodifiableSet())));

        var members = entries.stream().map(ScoredEntry::getValue).collect(Collectors.toUnmodifiableSet());

        return Mono.fromRunnable(() -> byWorkspace.forEach(projectService::recordLastUpdatedTrace))
                .subscribeOn(Schedulers.boundedElastic())
                .then(flushing.removeAll(members))
                .thenReturn((long) byWorkspace.values().stream().mapToInt(Collection::size).sum());
    }

    private RScoredSortedSetReactive<String> pendingSet() {
        return redisClient.getScoredSortedSet(ProjectLastUpdatedFlushConfig.PENDING_SET_KEY, StringCodec.INSTANCE);
    }

    private RScoredSortedSetReactive<String> flushingSet() {
        return redisClient.getScoredSortedSet(ProjectLastUpdatedFlushConfig.FLUSHING_SET_KEY, StringCodec.INSTANCE);
    }

    // Member is "workspaceId:projectId"; projectId is a UUID with no separator, so split on the last separator.
    private static ParsedMember parseMember(ScoredEntry<String> entry) {
        String value = entry.getValue();
        int separatorIndex = value.lastIndexOf(ProjectLastUpdatedFlushConfig.MEMBER_SEPARATOR);
        if (separatorIndex <= 0 || separatorIndex == value.length() - 1) {
            log.warn("Skipping malformed last-updated-trace buffer member: '{}'", value);
            return null;
        }
        try {
            return ParsedMember.builder()
                    .workspaceId(value.substring(0, separatorIndex))
                    .projectId(UUID.fromString(value.substring(separatorIndex + 1)))
                    .lastUpdatedAt(Instant.ofEpochMilli(entry.getScore().longValue()))
                    .build();
        } catch (IllegalArgumentException e) {
            log.warn("Skipping last-updated-trace buffer member with invalid project id: '{}'", value);
            return null;
        }
    }

    @Builder(toBuilder = true)
    private record ParsedMember(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull Instant lastUpdatedAt) {
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        interrupted.set(true);
        log.info("ProjectLastUpdatedFlushJob interrupted");
    }
}
