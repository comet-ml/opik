package com.comet.opik.domain;

import com.comet.opik.api.ExperimentItem;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.EventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Experiment Item Service")
class ExperimentItemServiceTest {

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
     */
    @Test
    @DisplayName("creating items should not block the subscribing thread while validation is in flight")
    void createDoesNotBlockTheSubscribingThread() throws Exception {
        var service = new ExperimentItemService(experimentItemDAO, experimentService, datasetItemDAO,
                datasetItemVersionDAO, traceDAO, projectService, featureFlags, eventBus);

        // Never completed during the assertion: the point is that subscribing returns regardless.
        var validationInFlight = new CompletableFuture<Boolean>();
        when(experimentService.validateExperimentWorkspace(any(), anySet()))
                .thenReturn(Mono.fromFuture(validationInFlight));

        var experimentItem = ExperimentItem.builder()
                .id(UUID.randomUUID())
                .experimentId(UUID.randomUUID())
                .datasetItemId(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .build();

        var subscribeReturned = new CompletableFuture<Void>();
        var subscriber = new Thread(() -> {
            service.create(Set.of(experimentItem))
                    .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, UUID.randomUUID().toString())
                            .put(RequestContext.USER_NAME, "user"))
                    .subscribe(__ -> {
                    }, __ -> {
                    });
            subscribeReturned.complete(null);
        });
        subscriber.start();

        try {
            assertThat(subscribeReturned.get(5, TimeUnit.SECONDS)).isNull();
        } finally {
            validationInFlight.complete(true);
            subscriber.join(TimeUnit.SECONDS.toMillis(5));
        }
    }
}
