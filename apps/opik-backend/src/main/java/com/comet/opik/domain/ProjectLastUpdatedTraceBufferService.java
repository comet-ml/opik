package com.comet.opik.domain;

import com.comet.opik.api.ProjectIdLastUpdated;
import com.comet.opik.infrastructure.ProjectLastUpdatedFlushConfig;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSetReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Collection;
import java.util.UUID;

/**
 * Buffers per-project last-updated-trace timestamps on the ingestion path, keeping the high-frequency trace events
 * off the contended {@code projects} MySQL row. When enabled, the latest timestamp per project is written to a Redis
 * ZSET (member {@code "workspaceId:projectId"}, score = epoch millis) via {@code addIfGreater}, which keeps only the
 * cluster-wide max; {@code ProjectLastUpdatedFlushJob} drains it to MySQL periodically. When disabled, delegates
 * straight to the synchronous MySQL write.
 */
@ImplementedBy(ProjectLastUpdatedTraceBufferServiceImpl.class)
public interface ProjectLastUpdatedTraceBufferService {

    void record(String workspaceId, Collection<ProjectIdLastUpdated> lastUpdatedTraces);
}

@Slf4j
@Singleton
class ProjectLastUpdatedTraceBufferServiceImpl implements ProjectLastUpdatedTraceBufferService {

    private final ProjectLastUpdatedFlushConfig config;
    private final RedissonReactiveClient redisClient;
    private final ProjectService projectService;

    @Inject
    public ProjectLastUpdatedTraceBufferServiceImpl(
            @NonNull @Config("projectLastUpdatedFlush") ProjectLastUpdatedFlushConfig config,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull ProjectService projectService) {
        this.config = config;
        this.redisClient = redisClient;
        this.projectService = projectService;
    }

    @Override
    public void record(String workspaceId, Collection<ProjectIdLastUpdated> lastUpdatedTraces) {
        if (lastUpdatedTraces.isEmpty()) {
            return;
        }

        if (!config.isEnabled()) {
            projectService.recordLastUpdatedTrace(workspaceId, lastUpdatedTraces);
            return;
        }

        RScoredSortedSetReactive<String> pending = redisClient.getScoredSortedSet(
                ProjectLastUpdatedFlushConfig.PENDING_SET_KEY, StringCodec.INSTANCE);
        Flux.fromIterable(lastUpdatedTraces)
                .flatMap(project -> pending.addIfGreater(
                        project.lastUpdatedAt().toEpochMilli(), member(workspaceId, project.id())))
                .subscribeOn(Schedulers.boundedElastic())
                // Fail-open: the marker is a best-effort sort hint, a Redis blip must not break trace ingestion.
                .subscribe(
                        __ -> {
                        },
                        error -> log.warn("Failed to buffer last-updated-trace marker for workspace '{}'",
                                workspaceId, error));
    }

    private static String member(String workspaceId, UUID projectId) {
        return workspaceId + ProjectLastUpdatedFlushConfig.MEMBER_SEPARATOR + projectId;
    }
}
