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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.eventbus.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamFactoryImpl;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Guards the OPIK-7029 review fix (thiagohora): {@code update()} must only take the distributed lock for
 * a metadata update (the read-modify-write that can lose keys), never for a status-only / name-only write.
 * A status write that depended on Redis would let a lock blip 500 the worker's mark_completed / mark_error
 * callback, leaving the run non-terminal for the stalled-run reaper to later mislabel {@code ERROR}.
 */
@ExtendWith(MockitoExtension.class)
class OptimizationServiceUpdateLockTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();

    private final PodamFactory factory = new PodamFactoryImpl();

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

    @Test
    @DisplayName("a status-only update persists without acquiring the Redis lock")
    void statusOnlyUpdate__doesNotAcquireLock() {
        var id = UUID.randomUUID();
        var existing = factory.manufacturePojo(Optimization.class).toBuilder()
                .id(id)
                .status(OptimizationStatus.INITIALIZED)
                .build();
        when(optimizationDAO.getById(id)).thenReturn(Mono.just(existing));
        when(optimizationDAO.update(eq(id), any())).thenReturn(Mono.just(1L));

        var update = OptimizationUpdate.builder().status(OptimizationStatus.RUNNING).build();

        StepVerifier.create(update(id, update)).expectNext(1L).verifyComplete();

        verify(optimizationDAO).update(eq(id), any());
        verifyNoInteractions(lockService);
    }

    @Test
    @DisplayName("a metadata update is serialized under the Redis lock")
    void metadataUpdate__acquiresLock() {
        var id = UUID.randomUUID();
        var existing = factory.manufacturePojo(Optimization.class).toBuilder()
                .id(id)
                .status(OptimizationStatus.RUNNING)
                .metadata(null)
                .build();
        when(optimizationDAO.getById(id)).thenReturn(Mono.just(existing));
        when(optimizationDAO.update(eq(id), any())).thenReturn(Mono.just(1L));
        // Pass the guarded action through so the update still completes under the (mocked) lock.
        when(lockService.executeWithLock(any(LockService.Lock.class), ArgumentMatchers.<Mono<Long>>any()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        JsonNode metadata = JsonNodeFactory.instance.objectNode().put("optimizer", "test");
        var update = OptimizationUpdate.builder().metadata(metadata).build();

        StepVerifier.create(update(id, update)).expectNext(1L).verifyComplete();

        verify(lockService).executeWithLock(any(LockService.Lock.class), ArgumentMatchers.<Mono<Long>>any());
    }
}
