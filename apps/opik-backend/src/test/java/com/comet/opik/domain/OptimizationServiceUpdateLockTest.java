package com.comet.opik.domain;

import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.domain.attachment.PreSignerService;
import com.comet.opik.domain.optimization.OptimizationLogSyncService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.queues.QueueProducer;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.common.eventbus.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.RedisException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the OPIK-7159/OPIK-7029 review decision for {@code update()}: every per-id state change is
 * serialized under the distributed lock so a rename can't race a status write (or the worker race the
 * reaper) into a lost update that strands a finished run non-terminal. The lock deliberately protects every
 * write rather than falling back to a lock-free path on a Redis outage — the stalled-run reaper is the
 * backstop for a run left non-terminal, so the write surfaces the error instead of risking a lost update.
 */
@ExtendWith(MockitoExtension.class)
class OptimizationServiceUpdateLockTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    @Mock
    private OptimizationDAO optimizationDAO;
    @Mock
    private DatasetService datasetService;
    @Mock
    private ProjectService projectService;
    @Mock
    private IdGenerator idGenerator;
    @Mock
    private NameGenerator nameGenerator;
    @Mock
    private EventBus eventBus;
    @Mock
    private PreSignerService preSignerService;
    @Mock
    private QueueProducer queueProducer;
    @Mock
    private WorkspaceNameService workspaceNameService;
    @Mock
    private OptimizationLogSyncService logSyncService;
    @Mock
    private RedissonReactiveClient redisClient;
    @Mock
    private AnalyticsService analyticsService;
    @Mock
    private LockService lockService;

    private OptimizationServiceImpl optimizationService;

    @BeforeEach
    void setUp() {
        optimizationService = new OptimizationServiceImpl(optimizationDAO, datasetService, projectService,
                idGenerator, nameGenerator, eventBus, preSignerService, queueProducer, workspaceNameService,
                new OpikConfiguration(), logSyncService, redisClient, analyticsService, lockService);
    }

    private Mono<Long> update(UUID id, OptimizationUpdate update) {
        return optimizationService.update(id, update)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));
    }

    private Optimization existing(UUID id) {
        return factory.manufacturePojo(Optimization.class).toBuilder()
                .id(id)
                .status(OptimizationStatus.INITIALIZED)
                .metadata(null)
                .build();
    }

    private void stubHappyPath(UUID id) {
        when(optimizationDAO.getById(id)).thenReturn(Mono.just(existing(id)));
        when(optimizationDAO.update(eq(id), any())).thenReturn(Mono.just(1L));
        // Pass the guarded action through so the update completes under the (mocked) lock.
        when(lockService.executeWithLock(any(LockService.Lock.class), ArgumentMatchers.<Mono<Long>>any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    @DisplayName("a status update is serialized under the distributed lock")
    void updateWhenStatusOnlyAcquiresLock() {
        var id = UUID.randomUUID();
        stubHappyPath(id);

        var update = OptimizationUpdate.builder().status(OptimizationStatus.RUNNING).build();

        StepVerifier.create(update(id, update)).expectNext(1L).verifyComplete();

        verify(lockService).executeWithLock(any(LockService.Lock.class), ArgumentMatchers.<Mono<Long>>any());
        verify(optimizationDAO).update(eq(id), any());
    }

    @Test
    @DisplayName("a name-only update is serialized under the distributed lock")
    void updateWhenNameOnlyAcquiresLock() {
        var id = UUID.randomUUID();
        stubHappyPath(id);

        var update = OptimizationUpdate.builder().name("renamed-optimization").build();

        StepVerifier.create(update(id, update)).expectNext(1L).verifyComplete();

        verify(lockService).executeWithLock(any(LockService.Lock.class), ArgumentMatchers.<Mono<Long>>any());
        verify(optimizationDAO).update(eq(id), any());
    }

    @Test
    @DisplayName("a lock-acquisition failure surfaces the error instead of writing lock-free")
    void updateWhenRedisUnavailableSurfacesError() {
        var id = UUID.randomUUID();
        // Lock acquisition fails as it would during a Redis outage.
        when(lockService.executeWithLock(any(LockService.Lock.class), ArgumentMatchers.<Mono<Long>>any()))
                .thenReturn(Mono.error(new RedisException("redis unavailable")));

        var update = OptimizationUpdate.builder().status(OptimizationStatus.RUNNING).build();

        // The write must NOT bypass the lock: surface the error rather than risk a lock-free lost update.
        StepVerifier.create(update(id, update)).expectError(RedisException.class).verify();

        verify(optimizationDAO, never()).update(any(), any());
    }
}
