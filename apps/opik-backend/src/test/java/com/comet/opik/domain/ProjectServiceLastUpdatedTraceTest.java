package com.comet.opik.domain;

import com.comet.opik.api.ProjectIdLastUpdated;
import com.comet.opik.api.sorting.SortingFactoryProjects;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.ProjectLastUpdatedFlushConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import io.dropwizard.util.Duration;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RScoredSortedSetReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.ScoredEntry;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectService last-updated-trace buffering/flush Tests")
class ProjectServiceLastUpdatedTraceTest {

    @Mock
    private TransactionTemplate template;
    @Mock
    private IdGenerator idGenerator;
    @Mock
    private Provider<RequestContext> requestContext;
    @Mock
    private TraceDAO traceDAO;
    @Mock
    private SortingFactoryProjects sortingFactory;
    @Mock
    private SortingQueryBuilder sortingQueryBuilder;
    @Mock
    private AnalyticsService analyticsService;
    @Mock
    private RedissonReactiveClient redisClient;
    @Mock
    private RScoredSortedSetReactive<String> pendingSet;
    @Mock
    private RScoredSortedSetReactive<String> flushingSet;

    private ProjectLastUpdatedFlushConfig config;
    private ProjectServiceImpl service;

    @BeforeEach
    void setUp() {
        config = buildConfig(true);
        service = newService(config);
    }

    private ProjectServiceImpl newService(ProjectLastUpdatedFlushConfig flushConfig) {
        return new ProjectServiceImpl(template, idGenerator, requestContext, traceDAO, sortingFactory,
                sortingQueryBuilder, analyticsService, redisClient, flushConfig);
    }

    private void stubPendingSet() {
        doReturn(pendingSet).when(redisClient)
                .getScoredSortedSet(eq(ProjectLastUpdatedFlushConfig.PENDING_SET_KEY), any(Codec.class));
    }

    // Route the live (PENDING) and snapshot (FLUSHING) keys to distinct mocks. flush() moves the live buffer into
    // the snapshot via renamenx, then drains the snapshot — so the drain reads/removes against flushingSet.
    private void stubFlushSets(boolean liveHasData) {
        stubPendingSet();
        doReturn(flushingSet).when(redisClient)
                .getScoredSortedSet(eq(ProjectLastUpdatedFlushConfig.FLUSHING_SET_KEY), any(Codec.class));
        when(pendingSet.isExists()).thenReturn(Mono.just(liveHasData));
        if (liveHasData) {
            when(pendingSet.renamenx(ProjectLastUpdatedFlushConfig.FLUSHING_SET_KEY)).thenReturn(Mono.just(true));
        }
    }

    @Nested
    @DisplayName("record")
    class Record {

        @Test
        @DisplayName("When disabled, writes to MySQL synchronously and never touches Redis")
        void record__whenDisabled__writesToDb() {
            service = newService(buildConfig(false));

            var workspaceId = UUID.randomUUID().toString();
            var traces = List.of(ProjectIdLastUpdated.builder()
                    .id(UUID.randomUUID())
                    .lastUpdatedAt(Instant.now())
                    .build());

            service.recordLastUpdatedTrace(workspaceId, traces);

            verify(template).inTransaction(eq(WRITE), any());
            verifyNoInteractions(redisClient);
        }

        @Test
        @DisplayName("When enabled, buffers each project in Redis with addIfGreater and never writes MySQL")
        void record__whenEnabled__buffersInRedis() {
            stubPendingSet();
            when(pendingSet.addIfGreater(anyDouble(), anyString())).thenReturn(Mono.just(true));

            var workspaceId = UUID.randomUUID().toString();
            var projectId1 = UUID.randomUUID();
            var projectId2 = UUID.randomUUID();
            var ts1 = Instant.ofEpochMilli(1_000L);
            var ts2 = Instant.ofEpochMilli(2_000L);

            service.recordLastUpdatedTrace(workspaceId, List.of(
                    ProjectIdLastUpdated.builder().id(projectId1).lastUpdatedAt(ts1).build(),
                    ProjectIdLastUpdated.builder().id(projectId2).lastUpdatedAt(ts2).build()));

            verify(pendingSet, timeout(1_000)).addIfGreater(1_000d, workspaceId + ":" + projectId1);
            verify(pendingSet, timeout(1_000)).addIfGreater(2_000d, workspaceId + ":" + projectId2);
            verify(template, never()).inTransaction(any(), any());
        }
    }

    @Nested
    @DisplayName("flushLastUpdatedTraces")
    class Flush {

        @Test
        @DisplayName("Groups a workspace's projects into a single MySQL write and removes flushed members")
        void flush__singleWorkspace__writesOnceAndRemoves() {
            stubFlushSets(true);

            var workspaceId = UUID.randomUUID().toString();
            var projectId1 = UUID.randomUUID();
            var projectId2 = UUID.randomUUID();
            var member1 = workspaceId + ":" + projectId1;
            var member2 = workspaceId + ":" + projectId2;

            when(flushingSet.entryRange(anyInt(), anyInt())).thenReturn(Mono.just(List.of(
                    new ScoredEntry<>(1_000d, member1),
                    new ScoredEntry<>(2_000d, member2))));
            when(flushingSet.removeAll(any())).thenReturn(Mono.just(true));

            Long written = service.flushLastUpdatedTraces().block();

            assertThat(written).isEqualTo(2L);
            verify(pendingSet).renamenx(ProjectLastUpdatedFlushConfig.FLUSHING_SET_KEY);
            verify(template, times(1)).inTransaction(eq(WRITE), any());
            verify(flushingSet).removeAll(Set.of(member1, member2));
        }

        @Test
        @DisplayName("Writes one MySQL batch per distinct workspace")
        void flush__multipleWorkspaces__writesPerWorkspace() {
            stubFlushSets(true);

            var workspace1 = UUID.randomUUID().toString();
            var workspace2 = UUID.randomUUID().toString();
            var member1 = workspace1 + ":" + UUID.randomUUID();
            var member2 = workspace2 + ":" + UUID.randomUUID();

            when(flushingSet.entryRange(anyInt(), anyInt())).thenReturn(Mono.just(List.of(
                    new ScoredEntry<>(1_000d, member1),
                    new ScoredEntry<>(2_000d, member2))));
            when(flushingSet.removeAll(any())).thenReturn(Mono.just(true));

            Long written = service.flushLastUpdatedTraces().block();

            assertThat(written).isEqualTo(2L);
            verify(template, times(2)).inTransaction(eq(WRITE), any());
        }

        @Test
        @DisplayName("When buffer is empty, performs no MySQL write and returns 0")
        void flush__emptyBuffer__writesNothing() {
            stubFlushSets(false); // live buffer absent → no rename; snapshot empty
            when(flushingSet.entryRange(anyInt(), anyInt())).thenReturn(Mono.just(List.of()));

            Long written = service.flushLastUpdatedTraces().block();

            assertThat(written).isZero();
            verify(pendingSet, never()).renamenx(any());
            verify(template, never()).inTransaction(any(), any());
            verify(flushingSet, never()).removeAll(any());
        }

        @Test
        @DisplayName("Skips a malformed member but still removes it from the buffer")
        void flush__malformedMember__skippedButRemoved() {
            stubFlushSets(true);

            var workspaceId = UUID.randomUUID().toString();
            var validMember = workspaceId + ":" + UUID.randomUUID();
            var malformedMember = workspaceId + ":not-a-uuid";

            when(flushingSet.entryRange(anyInt(), anyInt())).thenReturn(Mono.just(List.of(
                    new ScoredEntry<>(1_000d, validMember),
                    new ScoredEntry<>(2_000d, malformedMember))));
            when(flushingSet.removeAll(any())).thenReturn(Mono.just(true));

            Long written = service.flushLastUpdatedTraces().block();

            assertThat(written).isEqualTo(1L);
            verify(template, times(1)).inTransaction(eq(WRITE), any());
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
