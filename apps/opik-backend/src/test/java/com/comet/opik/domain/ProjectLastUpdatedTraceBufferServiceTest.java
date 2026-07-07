package com.comet.opik.domain;

import com.comet.opik.api.ProjectIdLastUpdated;
import com.comet.opik.infrastructure.ProjectLastUpdatedFlushConfig;
import com.comet.opik.infrastructure.redis.StringRedisClient;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RScoredSortedSet;
import org.redisson.client.protocol.ScoredEntry;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.ProjectLastUpdatedTraceBufferServiceImpl.FLUSHING_SET_KEY;
import static com.comet.opik.domain.ProjectLastUpdatedTraceBufferServiceImpl.PENDING_SET_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectLastUpdatedTraceBufferService Tests")
class ProjectLastUpdatedTraceBufferServiceTest {

    @Mock
    private StringRedisClient redisClient;
    @Mock
    private ProjectService projectService;
    @Mock
    private RScoredSortedSet<String> pendingSet;
    @Mock
    private RScoredSortedSet<String> flushingSet;

    private ProjectLastUpdatedTraceBufferServiceImpl newService(boolean enabled) {
        return new ProjectLastUpdatedTraceBufferServiceImpl(buildConfig(enabled), redisClient, projectService);
    }

    @Nested
    @DisplayName("record")
    class Record {

        @Test
        @DisplayName("When disabled, writes to MySQL synchronously and never touches Redis")
        void whenDisabled__writesToDb() {
            var workspaceId = UUID.randomUUID().toString();
            var traces = List.of(ProjectIdLastUpdated.builder()
                    .id(UUID.randomUUID())
                    .lastUpdatedAt(Instant.now())
                    .build());

            newService(false).record(workspaceId, traces);

            verify(projectService).recordLastUpdatedTrace(workspaceId, traces);
            verifyNoInteractions(redisClient);
        }

        @Test
        @DisplayName("When enabled, buffers each project in Redis with addIfGreater and never writes MySQL")
        void whenEnabled__buffersInRedis() {
            doReturn(pendingSet).when(redisClient).getScoredSortedSet(PENDING_SET_KEY);

            var workspaceId = UUID.randomUUID().toString();
            var projectId1 = UUID.randomUUID();
            var projectId2 = UUID.randomUUID();

            newService(true).record(workspaceId, List.of(
                    ProjectIdLastUpdated.builder().id(projectId1).lastUpdatedAt(Instant.ofEpochMilli(1_000)).build(),
                    ProjectIdLastUpdated.builder().id(projectId2).lastUpdatedAt(Instant.ofEpochMilli(2_000)).build()));

            verify(pendingSet).addIfGreater(1_000d, workspaceId + ":" + projectId1);
            verify(pendingSet).addIfGreater(2_000d, workspaceId + ":" + projectId2);
            verify(projectService, never()).recordLastUpdatedTrace(anyString(), any());
        }

        @Test
        @DisplayName("Null or empty input is a no-op")
        void whenNullOrEmpty__doesNothing() {
            var service = newService(true);

            service.record(UUID.randomUUID().toString(), null);
            service.record(UUID.randomUUID().toString(), List.of());

            verifyNoInteractions(redisClient);
            verifyNoInteractions(projectService);
        }
    }

    @Nested
    @DisplayName("flush")
    class Flush {

        @Test
        @DisplayName("Snapshots the live buffer, writes one MySQL batch per workspace, and removes members")
        void snapshotsAndWritesPerWorkspace() {
            doReturn(pendingSet).when(redisClient).getScoredSortedSet(PENDING_SET_KEY);
            doReturn(flushingSet).when(redisClient).getScoredSortedSet(FLUSHING_SET_KEY);
            when(pendingSet.isExists()).thenReturn(true);

            var workspaceId = UUID.randomUUID().toString();
            var projectId1 = UUID.randomUUID();
            var projectId2 = UUID.randomUUID();
            var member1 = workspaceId + ":" + projectId1;
            var member2 = workspaceId + ":" + projectId2;

            when(flushingSet.entryRange(anyInt(), anyInt())).thenReturn(List.of(
                    new ScoredEntry<>(1_000d, member1),
                    new ScoredEntry<>(2_000d, member2)));

            long written = newService(true).flush();

            assertThat(written).isEqualTo(2L);
            verify(pendingSet).renamenx(FLUSHING_SET_KEY);
            verify(projectService, times(1)).recordLastUpdatedTrace(eq(workspaceId), any());
            verify(flushingSet).removeAll(Set.of(member1, member2));
        }

        @Test
        @DisplayName("When there is nothing buffered, performs no MySQL write and no removal")
        void whenEmpty__writesNothing() {
            doReturn(pendingSet).when(redisClient).getScoredSortedSet(PENDING_SET_KEY);
            doReturn(flushingSet).when(redisClient).getScoredSortedSet(FLUSHING_SET_KEY);
            when(pendingSet.isExists()).thenReturn(false);
            when(flushingSet.entryRange(anyInt(), anyInt())).thenReturn(List.of());

            long written = newService(true).flush();

            assertThat(written).isZero();
            verify(pendingSet, never()).renamenx(any());
            verify(projectService, never()).recordLastUpdatedTrace(any(), any());
            verify(flushingSet, never()).removeAll(any());
        }

        @Test
        @DisplayName("Malformed member is skipped for the DB write but still removed from the snapshot")
        void malformedMember__skippedButRemoved() {
            doReturn(pendingSet).when(redisClient).getScoredSortedSet(PENDING_SET_KEY);
            doReturn(flushingSet).when(redisClient).getScoredSortedSet(FLUSHING_SET_KEY);
            when(pendingSet.isExists()).thenReturn(true);

            var workspaceId = UUID.randomUUID().toString();
            var validMember = workspaceId + ":" + UUID.randomUUID();
            var malformedMember = workspaceId + ":not-a-uuid";

            when(flushingSet.entryRange(anyInt(), anyInt())).thenReturn(List.of(
                    new ScoredEntry<>(1_000d, validMember),
                    new ScoredEntry<>(2_000d, malformedMember)));

            long written = newService(true).flush();

            assertThat(written).isEqualTo(1L);
            verify(projectService, times(1)).recordLastUpdatedTrace(eq(workspaceId), any());
            verify(flushingSet).removeAll(Set.of(validMember, malformedMember));
        }
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
