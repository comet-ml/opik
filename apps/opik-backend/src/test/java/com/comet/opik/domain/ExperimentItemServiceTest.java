package com.comet.opik.domain;

import com.comet.opik.api.ExperimentItem;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.google.common.eventbus.EventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Experiment Item Service")
class ExperimentItemServiceTest {

    private static final TimeBasedEpochGenerator UUID_GENERATOR = Generators.timeBasedEpochGenerator();

    @Mock
    private ExperimentItemDAO experimentItemDAO;
    @Mock
    private ExperimentService experimentService;
    @Mock
    private DatasetItemDAO datasetItemDAO;
    @Mock
    private DatasetItemVersionDAO datasetItemVersionDAO;
    @Mock
    private TraceDAO traceDAO;
    @Mock
    private ProjectService projectService;
    @Mock
    private FeatureFlags featureFlags;
    @Mock
    private EventBus eventBus;

    /**
     * Subscribing must not park the caller. Items are created from the thread that completed the
     * trace and span inserts, one of the database driver's own, and the validation queries need a
     * thread from that same pool: a blocking subscribe holds workers hostage to work only those
     * workers can do, and a run wide enough to hold all of them takes every read down with it.
     *
     * Both lookups answer through a future that is still pending while the assertions run, so a
     * blocking call on either parks the subscriber rather than returning at once — a stub that
     * completed synchronously would let one through. Run against both sides of the versioning
     * flag, since each reads a different table and either could regress.
     *
     * The chain is then released and asserted to complete without error, so the test cannot pass
     * on one that stopped short of the call it is about.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("creating items should not block the subscribing thread while validation is in flight")
    void createDoesNotBlockTheSubscribingThread(boolean datasetVersioningEnabled) throws Exception {
        var service = new ExperimentItemService(experimentItemDAO, experimentService, datasetItemDAO,
                datasetItemVersionDAO, traceDAO, projectService, featureFlags, eventBus);

        // Pending while the assertions run: the point is that subscribing returns regardless.
        var experimentWorkspaceInFlight = new CompletableFuture<Boolean>();
        var experimentWorkspaceSubscribed = new CountDownLatch(1);
        when(experimentService.validateExperimentWorkspace(any(), anySet()))
                .thenReturn(Mono.fromFuture(experimentWorkspaceInFlight)
                        .doOnSubscribe(subscription -> experimentWorkspaceSubscribed.countDown()));

        // Reached while the chain is assembled, so a blocking call here parks the subscriber
        // before the lookup above is ever subscribed.
        var datasetWorkspaceInFlight = new CompletableFuture<List<WorkspaceAndResourceId>>();
        when(featureFlags.isDatasetVersioningEnabled()).thenReturn(datasetVersioningEnabled);
        when(datasetItemDAO.getDatasetItemWorkspace(anySet()))
                .thenReturn(Mono.fromFuture(datasetWorkspaceInFlight));
        when(datasetItemVersionDAO.getDatasetItemWorkspace(anySet()))
                .thenReturn(Mono.fromFuture(datasetWorkspaceInFlight));

        var workspaceId = UUID.randomUUID().toString();
        var experimentItem = ExperimentItem.builder()
                .id(UUID_GENERATOR.generate())
                .experimentId(UUID_GENERATOR.generate())
                .datasetItemId(UUID_GENERATOR.generate())
                .traceId(UUID_GENERATOR.generate())
                .projectId(UUID_GENERATOR.generate())
                .build();

        when(experimentService.getExecutionPolicies(anySet())).thenReturn(Mono.just(Map.of()));
        when(experimentItemDAO.insert(anySet())).thenReturn(Mono.just(1L));

        var failure = new AtomicReference<Throwable>();
        var subscribeReturned = new CompletableFuture<Void>();
        var completed = new CountDownLatch(1);
        var subscriber = new Thread(() -> {
            service.create(Set.of(experimentItem))
                    .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                            .put(RequestContext.USER_NAME, "user"))
                    .subscribe(__ -> {
                    }, failure::set, completed::countDown);
            subscribeReturned.complete(null);
        });
        subscriber.start();

        assertThat(experimentWorkspaceSubscribed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(subscribeReturned.get(5, TimeUnit.SECONDS)).isNull();
        assertThat(failure.get()).isNull();

        // Released only now, so the rest of the chain runs and has to finish cleanly.
        experimentWorkspaceInFlight.complete(true);
        datasetWorkspaceInFlight.complete(List.of(new WorkspaceAndResourceId(workspaceId, experimentItem.id())));

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        subscriber.join(TimeUnit.SECONDS.toMillis(5));
    }
}
