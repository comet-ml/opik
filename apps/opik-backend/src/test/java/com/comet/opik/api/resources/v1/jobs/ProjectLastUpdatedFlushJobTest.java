package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.ProjectLastUpdatedFlushConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.redisson.api.RScoredSortedSetReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.ScoredEntry;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectLastUpdatedFlushJob Tests")
class ProjectLastUpdatedFlushJobTest {

    @Mock
    private RedissonReactiveClient redisClient;
    @Mock
    private ProjectService projectService;
    @Mock
    private LockService lockService;
    @Mock
    private JobExecutionContext jobContext;
    @Mock
    private RScoredSortedSetReactive<String> pendingSet;
    @Mock
    private RScoredSortedSetReactive<String> flushingSet;

    private ProjectLastUpdatedFlushJob newJob(boolean enabled) {
        return new ProjectLastUpdatedFlushJob(buildConfig(enabled), redisClient, projectService, lockService);
    }

    // Run the guarded action (arg 1 of the 6-arg bestEffortLock).
    private void runLockAction() {
        when(lockService.bestEffortLock(any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.<Mono<Void>>getArgument(1));
    }

    // Route the live (PENDING) and snapshot (FLUSHING) keys to distinct mocks.
    private void stubSets(boolean liveHasData) {
        doReturn(pendingSet).when(redisClient)
                .getScoredSortedSet(eq(ProjectLastUpdatedFlushConfig.PENDING_SET_KEY), any(Codec.class));
        doReturn(flushingSet).when(redisClient)
                .getScoredSortedSet(eq(ProjectLastUpdatedFlushConfig.FLUSHING_SET_KEY), any(Codec.class));
        when(pendingSet.isExists()).thenReturn(Mono.just(liveHasData));
        if (liveHasData) {
            when(pendingSet.renamenx(ProjectLastUpdatedFlushConfig.FLUSHING_SET_KEY)).thenReturn(Mono.just(true));
        }
    }

    @Nested
    @DisplayName("Guarding")
    class Guarding {

        @Test
        @DisplayName("When disabled, skips locking and draining entirely")
        void doJob__whenDisabled__skips() {
            newJob(false).doJob(jobContext);

            verify(lockService, never()).bestEffortLock(any(), any(), any(), any(), any(), anyBoolean());
            verify(redisClient, never()).getScoredSortedSet(any(), any(Codec.class));
        }

        @Test
        @DisplayName("When the lock cannot be acquired, does not drain")
        void doJob__whenLockNotAcquired__doesNotDrain() {
            when(lockService.bestEffortLock(any(), any(), any(), any(), any(), anyBoolean()))
                    .thenAnswer(invocation -> invocation.<Mono<Void>>getArgument(2));

            newJob(true).doJob(jobContext);

            verify(redisClient, never()).getScoredSortedSet(any(), any(Codec.class));
        }
    }

    @Nested
    @DisplayName("Draining")
    class Draining {

        @Test
        @DisplayName("Snapshots the live buffer and writes one MySQL batch per workspace, then removes members")
        void doJob__drainsSnapshotToDb() {
            runLockAction();
            stubSets(true);

            var workspaceId = UUID.randomUUID().toString();
            var projectId1 = UUID.randomUUID();
            var projectId2 = UUID.randomUUID();
            var member1 = workspaceId + ":" + projectId1;
            var member2 = workspaceId + ":" + projectId2;

            when(flushingSet.entryRange(anyInt(), anyInt())).thenReturn(Mono.just(List.of(
                    new ScoredEntry<>(1_000d, member1),
                    new ScoredEntry<>(2_000d, member2))));
            when(flushingSet.removeAll(any())).thenReturn(Mono.just(true));

            newJob(true).doJob(jobContext);

            // The batch write + removal run on boundedElastic (fire-and-forget subscribe), so await them.
            verify(projectService, timeout(1_000).times(1)).recordLastUpdatedTrace(eq(workspaceId), any());
            verify(flushingSet, timeout(1_000)).removeAll(Set.of(member1, member2));
            verify(pendingSet).renamenx(ProjectLastUpdatedFlushConfig.FLUSHING_SET_KEY);
        }

        @Test
        @DisplayName("When there is nothing buffered, performs no MySQL write and no removal")
        void doJob__whenEmpty__writesNothing() {
            runLockAction();
            stubSets(false); // live buffer absent → no rename
            when(flushingSet.entryRange(anyInt(), anyInt())).thenReturn(Mono.just(List.of()));

            newJob(true).doJob(jobContext);

            verify(pendingSet, never()).renamenx(any());
            verify(projectService, never()).recordLastUpdatedTrace(any(), any());
            verify(flushingSet, never()).removeAll(any());
        }

        @Test
        @DisplayName("Malformed member is skipped for the DB write but still removed from the snapshot")
        void doJob__malformedMember__skippedButRemoved() {
            runLockAction();
            stubSets(true);

            var workspaceId = UUID.randomUUID().toString();
            var validMember = workspaceId + ":" + UUID.randomUUID();
            var malformedMember = workspaceId + ":not-a-uuid";

            when(flushingSet.entryRange(anyInt(), anyInt())).thenReturn(Mono.just(List.of(
                    new ScoredEntry<>(1_000d, validMember),
                    new ScoredEntry<>(2_000d, malformedMember))));
            when(flushingSet.removeAll(any())).thenReturn(Mono.just(true));

            newJob(true).doJob(jobContext);

            verify(projectService, timeout(1_000).times(1)).recordLastUpdatedTrace(eq(workspaceId), any());
            verify(flushingSet, timeout(1_000)).removeAll(Set.of(validMember, malformedMember));
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
