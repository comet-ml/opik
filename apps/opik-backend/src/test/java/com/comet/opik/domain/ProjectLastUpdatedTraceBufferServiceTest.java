package com.comet.opik.domain;

import com.comet.opik.api.ProjectIdLastUpdated;
import com.comet.opik.infrastructure.ProjectLastUpdatedFlushConfig;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RScoredSortedSetReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.Codec;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectLastUpdatedTraceBufferService Tests")
class ProjectLastUpdatedTraceBufferServiceTest {

    @Mock
    private RedissonReactiveClient redisClient;
    @Mock
    private ProjectService projectService;
    @Mock
    private RScoredSortedSetReactive<String> pendingSet;

    private ProjectLastUpdatedTraceBufferServiceImpl newBuffer(boolean enabled) {
        return new ProjectLastUpdatedTraceBufferServiceImpl(buildConfig(enabled), redisClient, projectService);
    }

    @Test
    @DisplayName("When disabled, writes to MySQL synchronously and never touches Redis")
    void record__whenDisabled__writesToDb() {
        var buffer = newBuffer(false);

        var workspaceId = UUID.randomUUID().toString();
        var traces = List.of(ProjectIdLastUpdated.builder()
                .id(UUID.randomUUID())
                .lastUpdatedAt(Instant.now())
                .build());

        buffer.record(workspaceId, traces);

        verify(projectService).recordLastUpdatedTrace(workspaceId, traces);
        verifyNoInteractions(redisClient);
    }

    @Test
    @DisplayName("When enabled, buffers each project in Redis with addIfGreater and never writes MySQL")
    void record__whenEnabled__buffersInRedis() {
        var buffer = newBuffer(true);

        doReturn(pendingSet).when(redisClient)
                .getScoredSortedSet(eq(ProjectLastUpdatedFlushConfig.PENDING_SET_KEY), any(Codec.class));
        when(pendingSet.addIfGreater(anyDouble(), anyString())).thenReturn(Mono.just(true));

        var workspaceId = UUID.randomUUID().toString();
        var projectId1 = UUID.randomUUID();
        var projectId2 = UUID.randomUUID();

        buffer.record(workspaceId, List.of(
                ProjectIdLastUpdated.builder().id(projectId1).lastUpdatedAt(Instant.ofEpochMilli(1_000)).build(),
                ProjectIdLastUpdated.builder().id(projectId2).lastUpdatedAt(Instant.ofEpochMilli(2_000)).build()));

        verify(pendingSet, timeout(1_000)).addIfGreater(1_000d, workspaceId + ":" + projectId1);
        verify(pendingSet, timeout(1_000)).addIfGreater(2_000d, workspaceId + ":" + projectId2);
        verify(projectService, never()).recordLastUpdatedTrace(anyString(), any());
    }

    @Test
    @DisplayName("Empty input is a no-op")
    void record__whenEmpty__doesNothing() {
        var buffer = newBuffer(true);

        buffer.record(UUID.randomUUID().toString(), List.of());

        verifyNoInteractions(redisClient);
        verifyNoInteractions(projectService);
    }

    private static ProjectLastUpdatedFlushConfig buildConfig(boolean enabled) {
        var config = new ProjectLastUpdatedFlushConfig();
        config.setEnabled(enabled);
        config.setJobInterval(Duration.seconds(30));
        config.setJobLockTime(Duration.seconds(25));
        config.setJobLockWaitTime(Duration.milliseconds(500));
        config.setJobBatchSize(500);
        return config;
    }
}
