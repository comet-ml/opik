package com.comet.opik.domain;

import com.comet.opik.api.ProjectIdLastUpdated;
import com.comet.opik.infrastructure.ProjectLastUpdatedFlushConfig;
import com.comet.opik.infrastructure.redis.StringRedisClient;
import com.google.inject.ImplementedBy;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.redisson.api.RScoredSortedSet;
import org.redisson.client.protocol.ScoredEntry;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Owns the {@code projects.last_updated_trace_at} buffering: the ingestion-path write that keeps the marker off the
 * contended {@code projects} MySQL row, and the periodic flush that persists it.
 * <p>
 * When enabled, {@link #record} writes the latest timestamp per project to a Redis ZSET (member
 * {@code "workspaceId:projectId"}, score = epoch millis) via {@code addIfGreater}, which keeps only the cluster-wide
 * max; {@link #flush} (driven by {@code ProjectLastUpdatedFlushJob}) drains it to MySQL. When disabled,
 * {@link #record} delegates straight to the synchronous MySQL write.
 */
@ImplementedBy(ProjectLastUpdatedTraceBufferServiceImpl.class)
public interface ProjectLastUpdatedTraceBufferService {

    void record(String workspaceId, Collection<ProjectIdLastUpdated> lastUpdatedTraces);

    /** Drains the Redis buffer to MySQL and returns the number of project markers processed (attempted). */
    long flush();
}

@Slf4j
@Singleton
class ProjectLastUpdatedTraceBufferServiceImpl implements ProjectLastUpdatedTraceBufferService {

    static final String PENDING_SET_KEY = "project:last-updated-trace:pending";
    // Snapshot the live buffer is atomically renamed to while a flush drains it (renamenx), so writers keep
    // populating PENDING_SET_KEY and the drain owns its data exclusively.
    static final String FLUSHING_SET_KEY = "project:last-updated-trace:flushing";
    private static final String MEMBER_SEPARATOR = ":";

    static final String METER_NAME = "opik.project_last_updated_flush";
    private static final AttributeKey<String> RESULT_KEY = stringKey("result");

    private final ProjectLastUpdatedFlushConfig config;
    private final StringRedisClient redisClient;
    private final ProjectService projectService;

    private final LongCounter bufferedRecords;
    private final LongCounter flushedMarkers;

    @Inject
    public ProjectLastUpdatedTraceBufferServiceImpl(
            @NonNull @Config("projectLastUpdatedFlush") ProjectLastUpdatedFlushConfig config,
            @NonNull StringRedisClient redisClient,
            @NonNull ProjectService projectService) {
        this.config = config;
        this.redisClient = redisClient;
        this.projectService = projectService;

        var meter = GlobalOpenTelemetry.get().getMeter(METER_NAME);
        this.bufferedRecords = meter
                .counterBuilder(METER_NAME + ".buffered")
                .setDescription("Project last-updated markers buffered to Redis on the ingestion path, by result")
                .build();
        // Cumulative throughput of markers persisted over time (monotonic) — a counter, not a point-in-time gauge.
        this.flushedMarkers = meter
                .counterBuilder(METER_NAME + ".markers")
                .setDescription("Total project last-updated markers processed by the flush")
                .build();
    }

    @Override
    public void record(@NonNull String workspaceId, Collection<ProjectIdLastUpdated> lastUpdatedTraces) {
        if (CollectionUtils.isEmpty(lastUpdatedTraces)) {
            return;
        }

        if (!config.isEnabled()) {
            projectService.recordLastUpdatedTrace(workspaceId, lastUpdatedTraces);
            return;
        }

        // Count each marker once by its actual outcome so a mid-batch Redis failure doesn't mislabel the whole batch:
        // the failing marker is "error", already-buffered ones stay "ok", and the un-attempted remainder isn't counted.
        // The exception still propagates to the caller (the event bus), which owns the handling — Redis failures are
        // no longer swallowed here (see #7361 review feedback).
        RScoredSortedSet<String> pending = redisClient.getScoredSortedSet(PENDING_SET_KEY);
        for (ProjectIdLastUpdated project : lastUpdatedTraces) {
            try {
                pending.addIfGreater(project.lastUpdatedAt().toEpochMilli(), member(workspaceId, project.id()));
                bufferedRecords.add(1, Attributes.of(RESULT_KEY, "ok"));
            } catch (RuntimeException e) {
                bufferedRecords.add(1, Attributes.of(RESULT_KEY, "error"));
                throw e;
            }
        }
    }

    @Override
    public long flush() {
        RScoredSortedSet<String> pending = redisClient.getScoredSortedSet(PENDING_SET_KEY);
        // Move the live buffer aside atomically so concurrent record() calls keep populating a fresh live key while we
        // drain the snapshot exclusively (no read-then-remove race). A snapshot left by a previous interrupted flush
        // is drained instead (renamenx is a no-op when it exists); idempotent since the DB write only moves forward.
        if (Boolean.TRUE.equals(pending.isExists())) {
            pending.renamenx(FLUSHING_SET_KEY);
        }

        RScoredSortedSet<String> flushing = redisClient.getScoredSortedSet(FLUSHING_SET_KEY);
        int batchSize = config.getJobBatchSize();
        long written = 0;
        // Drain a page at a time, removing each page before reading the next so removal guarantees forward progress.
        // Loop while a full page comes back (there may be more); a short or empty page means the snapshot is drained.
        int pageSize;
        do {
            Collection<ScoredEntry<String>> entries = flushing.entryRange(0, batchSize - 1);
            pageSize = entries.size();
            if (pageSize > 0) {
                written += flushBatch(flushing, entries);
            }
        } while (pageSize == batchSize);
        if (written > 0) {
            flushedMarkers.add(written);
        }
        return written;
    }

    private long flushBatch(RScoredSortedSet<String> flushing, Collection<ScoredEntry<String>> entries) {
        // Group by workspace so each MySQL batch write targets a single workspace_id (matches the DAO binding).
        var byWorkspace = entries.stream()
                .map(ProjectLastUpdatedTraceBufferServiceImpl::parseMember)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        ParsedMember::workspaceId,
                        Collectors.mapping(
                                parsed -> ProjectIdLastUpdated.builder()
                                        .id(parsed.projectId())
                                        .lastUpdatedAt(parsed.lastUpdatedAt())
                                        .build(),
                                Collectors.toUnmodifiableSet())));

        byWorkspace.forEach(projectService::recordLastUpdatedTrace);

        flushing.removeAll(entries.stream().map(ScoredEntry::getValue).collect(Collectors.toUnmodifiableSet()));
        // Count of markers processed, not rows updated: the MySQL write is a monotonic UPDATE that skips rows already
        // ahead, and the DAO's affected-row counts are not propagated here.
        return byWorkspace.values().stream().mapToInt(Collection::size).sum();
    }

    private static String member(String workspaceId, UUID projectId) {
        return workspaceId + MEMBER_SEPARATOR + projectId;
    }

    // Member is "workspaceId:projectId"; projectId is a UUID with no separator, so split on the last separator.
    private static ParsedMember parseMember(ScoredEntry<String> entry) {
        String value = entry.getValue();
        int separatorIndex = value.lastIndexOf(MEMBER_SEPARATOR);
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
}
