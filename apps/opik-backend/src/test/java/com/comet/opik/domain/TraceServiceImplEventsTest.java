package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.TraceBatchUpdate;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.events.TraceCostIntelligenceChanged;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.domain.attachment.AttachmentReinjectorService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.attachment.AttachmentStripperService;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.FixedOrganizationResolver;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.eventbus.EventBus;
import io.r2dbc.spi.Connection;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TraceService: when and in what order trace writes post their events")
class TraceServiceImplEventsTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private TraceDAO traceDao;
    @Mock
    private DeletionEventDAO deletionEventDAO;
    @Mock
    private TransactionTemplateAsync template;
    @Mock
    private ProjectService projectService;
    @Mock
    private EventBus eventBus;
    @Mock
    private AttachmentStripperService attachmentStripperService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private AttachmentReinjectorService attachmentReinjectorService;

    private final Connection connection = mock(Connection.class);
    private final CountDownLatch written = new CountDownLatch(1);
    private final String workspaceId = UUID.randomUUID().toString();
    private final String userName = RandomStringUtils.secure().nextAlphanumeric(10);
    private final String organizationId = UUID.randomUUID().toString();
    private final Project project = Project.builder()
            .id(ID_GENERATOR.generateId())
            .name(RandomStringUtils.secure().nextAlphanumeric(10))
            .build();

    @RequiredArgsConstructor
    enum Write {
        CREATE(TracesCreated.class),
        BATCH_CREATE(TracesCreated.class),
        UPDATE(TracesUpdated.class),
        BATCH_UPDATE(TracesUpdated.class);

        private final Class<?> event;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Write.class)
    @DisplayName("Responds and posts the event without waiting for the organization lookup")
    void respondsWithoutWaitingForTheOrganizationLookup(Write write) {
        var traceService = traceService(new FixedOrganizationResolver(Mono.never()));

        write(traceService, write).block(RESPONSE_TIMEOUT);

        verify(eventBus).post(any(write.event));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Write.class)
    @DisplayName("Looks nothing up while responding: only a listener that needs the organization subscribes")
    void looksNothingUpWhileResponding(Write write) {
        var resolver = FixedOrganizationResolver.of(organizationId);

        write(traceService(resolver), write).block(RESPONSE_TIMEOUT);

        verify(eventBus).post(any(write.event));
        assertThat(resolver.lookups()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Write.class)
    @DisplayName("Posts the event when the request is cancelled right after the write")
    void postsTheEventWhenCancelledRightAfterTheWrite(Write write) throws InterruptedException {
        var traceService = traceService(new FixedOrganizationResolver(Mono.never()));

        var response = write(traceService, write).subscribe();
        assertThat(written.await(RESPONSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        response.dispose();

        verify(eventBus).post(any(write.event));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Write.class, names = {"UPDATE", "BATCH_UPDATE"})
    @DisplayName("Posts TracesUpdated before TraceCostIntelligenceChanged, as the write happened")
    void postsTracesUpdatedBeforeTraceCostIntelligenceChanged(Write write) {
        write(traceService(FixedOrganizationResolver.of(organizationId)), write).block(RESPONSE_TIMEOUT);

        var inOrder = inOrder(eventBus);
        inOrder.verify(eventBus).post(any(TracesUpdated.class));
        inOrder.verify(eventBus).post(any(TraceCostIntelligenceChanged.class));
    }

    @Test
    @DisplayName("Two overlapping updates of one trace post their TracesUpdated in the order they wrote")
    void overlappingUpdatesPostInWriteOrder() throws InterruptedException {
        var lookups = new AtomicInteger();
        var firstLookupIsSlow = Mono.defer(() -> lookups.getAndIncrement() == 0
                ? Mono.delay(Duration.ofMillis(500)).thenReturn(organizationId)
                : Mono.just(organizationId));
        var traceService = traceService(new FixedOrganizationResolver(firstLookupIsSlow));
        var traceId = ID_GENERATOR.generateId();
        var first = traceUpdate();
        var second = traceUpdate();
        mockTransaction();
        mockUpdate(traceId, first);
        mockUpdate(traceId, second);
        var firstDone = new CountDownLatch(1);

        inRequest(traceService.update(first, traceId)).doFinally(__ -> firstDone.countDown()).subscribe();
        assertThat(written.await(RESPONSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        inRequest(traceService.update(second, traceId)).block(RESPONSE_TIMEOUT);
        assertThat(firstDone.await(RESPONSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        var events = ArgumentCaptor.forClass(Object.class);
        verify(eventBus, atLeastOnce()).post(events.capture());
        assertThat(events.getAllValues().stream()
                .filter(TracesUpdated.class::isInstance)
                .map(event -> ((TracesUpdated) event).traceUpdate())
                .toList())
                .containsExactly(first, second);
    }

    private TraceServiceImpl traceService(FixedOrganizationResolver organizationResolver) {
        var opikConfiguration = new OpikConfiguration();
        opikConfiguration.setDatabaseAnalyticsDataModel(DatabaseAnalyticsDataModelConfig.builder().build());
        return new TraceServiceImpl(traceDao, deletionEventDAO, template, projectService, ID_GENERATOR,
                new WriteSignallingLockService(), eventBus, new TraceSortingFactory(), attachmentStripperService,
                attachmentService, attachmentReinjectorService, opikConfiguration, organizationResolver);
    }

    private Mono<?> write(TraceServiceImpl traceService, Write write) {
        return switch (write) {
            case CREATE -> create(traceService);
            case BATCH_CREATE -> batchCreate(traceService);
            case UPDATE -> update(traceService);
            case BATCH_UPDATE -> batchUpdate(traceService);
        };
    }

    private Mono<?> create(TraceServiceImpl traceService) {
        var trace = trace();
        when(projectService.getOrCreate(project.name())).thenReturn(Mono.just(project));
        when(attachmentStripperService.stripAttachments(any(Trace.class), eq(workspaceId), eq(userName),
                eq(project.name()))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(traceDao.getPartialById(trace.id())).thenReturn(Mono.empty());
        mockTransaction();
        when(traceDao.insert(any(Trace.class), eq(connection))).thenReturn(Mono.just(trace.id()));
        return inRequest(traceService.create(trace));
    }

    private Mono<?> batchCreate(TraceServiceImpl traceService) {
        var trace = trace();
        when(attachmentService.deleteAutoStrippedAttachments(EntityType.TRACE, Set.of(trace.id())))
                .thenReturn(Mono.just(0L));
        when(projectService.getOrCreate(project.name())).thenReturn(Mono.just(project));
        when(attachmentStripperService.stripAttachments(any(Trace.class), eq(workspaceId), eq(userName),
                eq(project.name()))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(traceDao.batchInsert(any())).thenReturn(Mono.just(1L).doOnSuccess(__ -> written.countDown()));
        return inRequest(traceService.create(TraceBatch.builder().traces(List.of(trace)).build()));
    }

    private Mono<?> update(TraceServiceImpl traceService) {
        var traceId = ID_GENERATOR.generateId();
        var traceUpdate = traceUpdate();
        mockTransaction();
        mockUpdate(traceId, traceUpdate);
        return inRequest(traceService.update(traceUpdate, traceId));
    }

    private void mockUpdate(UUID traceId, TraceUpdate traceUpdate) {
        when(projectService.getOrCreate(project.name())).thenReturn(Mono.just(project));
        when(traceDao.getPartialById(traceId)).thenReturn(Mono.empty());
        when(attachmentStripperService.stripAttachments(traceUpdate, traceId, workspaceId, userName, project.name()))
                .thenReturn(Mono.just(traceUpdate));
        when(traceDao.partialInsert(project.id(), traceUpdate, traceId, connection)).thenReturn(Mono.empty());
    }

    private Mono<?> batchUpdate(TraceServiceImpl traceService) {
        var traceId = ID_GENERATOR.generateId();
        var traceUpdate = traceUpdate();
        when(traceDao.getProjectIdsByTraceIds(List.of(traceId))).thenReturn(Mono.just(Map.of(traceId, project.id())));
        when(traceDao.bulkUpdate(Set.of(traceId), traceUpdate, false))
                .thenReturn(Mono.<Void>empty().doOnSuccess(__ -> written.countDown()));
        return inRequest(traceService.batchUpdate(TraceBatchUpdate.builder()
                .ids(Set.of(traceId))
                .update(traceUpdate)
                .build()));
    }

    private Trace trace() {
        return Trace.builder()
                .id(ID_GENERATOR.generateId())
                .projectName(project.name())
                .name(RandomStringUtils.secure().nextAlphanumeric(10))
                .startTime(Instant.now())
                .build();
    }

    private TraceUpdate traceUpdate() {
        return TraceUpdate.builder()
                .projectName(project.name())
                .name(RandomStringUtils.secure().nextAlphanumeric(10))
                .endTime(Instant.now())
                .build();
    }

    private void mockTransaction() {
        doAnswer(invocation -> {
            TransactionTemplateAsync.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.execute(connection);
        }).when(template).nonTransaction(any());
    }

    private <T> Mono<T> inRequest(Mono<T> write) {
        return write.contextWrite(ctx -> ctx
                .put(RequestContext.USER_NAME, userName)
                .put(RequestContext.WORKSPACE_ID, workspaceId));
    }

    /**
     * Signals once the locked write and what runs with it under the lock are done, so a test can cancel the request
     * right after the write, when the lock would be released.
     */
    private class WriteSignallingLockService extends DummyLockService {

        @Override
        public <T> Mono<T> executeWithLock(LockService.Lock lock, Mono<T> action) {
            return action.doOnSuccess(__ -> written.countDown());
        }
    }
}
