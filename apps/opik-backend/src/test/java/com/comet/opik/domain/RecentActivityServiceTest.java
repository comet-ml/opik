package com.comet.opik.domain;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.LogItem;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.RecentActivity.ActivityType;
import com.comet.opik.api.RecentActivity.RecentActivityItem;
import com.comet.opik.domain.alerts.AlertEventLogsDAO;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecentActivityServiceTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Mock
    private ExperimentService experimentService;
    @Mock
    private OptimizationService optimizationService;
    @Mock
    private AlertEventLogsDAO alertEventLogsDAO;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private InstantToUUIDMapper instantToUUIDMapper;
    @Mock
    private Provider<RequestContext> requestContextProvider;
    @Mock
    private RequestContext requestContext;

    @InjectMocks
    private RecentActivityService service;

    @BeforeEach
    void setUp() {
        when(requestContextProvider.get()).thenReturn(requestContext);
        when(requestContext.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(instantToUUIDMapper.toLowerBound(any(Instant.class))).thenReturn(UUID.randomUUID());
    }

    private void mockEmptyJdbiSources() {
        when(transactionTemplate.inTransaction(any(), any()))
                .thenReturn(List.of());
    }

    @Nested
    @DisplayName("Merge and sort:")
    class MergeAndSort {

        @Test
        void shouldMergeAllSourcesAndSortByCreatedAtDescending() {
            var now = Instant.now();
            var oldest = now.minusSeconds(300);
            var middle = now.minusSeconds(100);
            var newest = now;

            var experiment = podamFactory.manufacturePojo(Experiment.class).toBuilder()
                    .createdAt(middle).build();
            when(experimentService.find(eq(1), eq(10),
                    argThat(c -> PROJECT_ID.equals(c.projectId()))))
                    .thenReturn(Mono.just(new Experiment.ExperimentPage(1, 1, 1, List.of(experiment), List.of())));

            var optimization = podamFactory.manufacturePojo(Optimization.class).toBuilder()
                    .createdAt(newest).build();
            when(optimizationService.find(eq(1), eq(10),
                    argThat(c -> PROJECT_ID.equals(c.projectId()))))
                    .thenReturn(
                            Mono.just(new Optimization.OptimizationPage(1, 1, 1, List.of(optimization), List.of())));

            var alertId = UUID.randomUUID();
            var alertLog = LogItem.builder()
                    .timestamp(oldest).message("alert fired")
                    .markers(Map.of(UserLog.ALERT_ID, alertId.toString()))
                    .build();
            when(alertEventLogsDAO.findLogs(
                    argThat(c -> c.markers().containsValue(PROJECT_ID.toString()))))
                    .thenReturn(Flux.just(alertLog));

            mockEmptyJdbiSources();

            StepVerifier.create(service.getRecentActivity(PROJECT_ID, 1, 10))
                    .assertNext(result -> {
                        assertThat(result.page()).isEqualTo(1);
                        assertThat(result.size()).isEqualTo(10);
                        assertThat(result.total()).isEqualTo(3);

                        assertThat(result.content()).containsExactly(
                                RecentActivityItem.builder()
                                        .type(ActivityType.OPTIMIZATION).id(optimization.id())
                                        .name(optimization.datasetName()).createdAt(newest)
                                        .createdBy(optimization.createdBy()).build(),
                                RecentActivityItem.builder()
                                        .type(ActivityType.EXPERIMENT).id(experiment.id())
                                        .name(experiment.name()).createdAt(middle)
                                        .resourceId(experiment.datasetId())
                                        .createdBy(experiment.createdBy()).build(),
                                RecentActivityItem.builder()
                                        .type(ActivityType.ALERT_EVENT).id(alertId)
                                        .name("alert fired").createdAt(oldest).build());
                    })
                    .verifyComplete();
        }

        @Test
        void shouldLimitResultsToRequestedSize() {
            var now = Instant.now();
            var experiments = List.of(
                    podamFactory.manufacturePojo(Experiment.class).toBuilder().createdAt(now.minusSeconds(1)).build(),
                    podamFactory.manufacturePojo(Experiment.class).toBuilder().createdAt(now.minusSeconds(2)).build(),
                    podamFactory.manufacturePojo(Experiment.class).toBuilder().createdAt(now.minusSeconds(3)).build());
            when(experimentService.find(eq(1), eq(2),
                    argThat(c -> PROJECT_ID.equals(c.projectId()))))
                    .thenReturn(Mono.just(new Experiment.ExperimentPage(1, 3, 3, experiments, List.of())));

            when(optimizationService.find(eq(1), eq(2),
                    argThat(c -> PROJECT_ID.equals(c.projectId()))))
                    .thenReturn(Mono.just(Optimization.OptimizationPage.empty(1, List.of())));
            when(alertEventLogsDAO.findLogs(any())).thenReturn(Flux.empty());
            mockEmptyJdbiSources();

            StepVerifier.create(service.getRecentActivity(PROJECT_ID, 1, 2))
                    .assertNext(result -> {
                        assertThat(result.content()).hasSize(2);
                        assertThat(result.total()).isEqualTo(3);
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Graceful degradation:")
    class GracefulDegradation {

        @Test
        void shouldReturnPartialResultsWhenOneSourceFails() {
            var optimization = podamFactory.manufacturePojo(Optimization.class).toBuilder()
                    .createdAt(Instant.now()).build();
            when(optimizationService.find(eq(1), eq(10),
                    argThat(c -> PROJECT_ID.equals(c.projectId()))))
                    .thenReturn(
                            Mono.just(new Optimization.OptimizationPage(1, 1, 1, List.of(optimization), List.of())));

            when(experimentService.find(eq(1), eq(10),
                    argThat(c -> PROJECT_ID.equals(c.projectId()))))
                    .thenReturn(Mono.error(new RuntimeException("ClickHouse down")));
            when(alertEventLogsDAO.findLogs(any())).thenReturn(Flux.empty());
            mockEmptyJdbiSources();

            StepVerifier.create(service.getRecentActivity(PROJECT_ID, 1, 10))
                    .assertNext(result -> {
                        assertThat(result.content()).containsExactly(
                                RecentActivityItem.builder()
                                        .type(ActivityType.OPTIMIZATION).id(optimization.id())
                                        .name(optimization.datasetName()).createdAt(optimization.createdAt())
                                        .createdBy(optimization.createdBy()).build());
                    })
                    .verifyComplete();
        }

        @Test
        void shouldReturnEmptyWhenAllSourcesFail() {
            when(experimentService.find(anyInt(), anyInt(), any()))
                    .thenReturn(Mono.error(new RuntimeException("fail")));
            when(optimizationService.find(anyInt(), anyInt(), any()))
                    .thenReturn(Mono.error(new RuntimeException("fail")));
            when(alertEventLogsDAO.findLogs(any()))
                    .thenReturn(Flux.error(new RuntimeException("fail")));
            when(transactionTemplate.inTransaction(any(), any()))
                    .thenThrow(new RuntimeException("fail"));

            StepVerifier.create(service.getRecentActivity(PROJECT_ID, 1, 10))
                    .assertNext(result -> assertThat(result.content()).isEmpty())
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Alert event filtering:")
    class AlertEventFiltering {

        @Test
        void shouldSkipAlertEventsWithoutAlertIdMarker() {
            var validAlert = LogItem.builder()
                    .timestamp(Instant.now()).message("valid alert")
                    .markers(Map.of(UserLog.ALERT_ID, UUID.randomUUID().toString()))
                    .build();
            var invalidAlert = LogItem.builder()
                    .timestamp(Instant.now()).message("missing marker")
                    .markers(Map.of())
                    .build();
            var nullMarkersAlert = LogItem.builder()
                    .timestamp(Instant.now()).message("null markers")
                    .markers(null)
                    .build();

            when(alertEventLogsDAO.findLogs(any()))
                    .thenReturn(Flux.just(validAlert, invalidAlert, nullMarkersAlert));
            when(experimentService.find(anyInt(), anyInt(), any()))
                    .thenReturn(Mono.just(Experiment.ExperimentPage.empty(1, List.of())));
            when(optimizationService.find(anyInt(), anyInt(), any()))
                    .thenReturn(Mono.just(Optimization.OptimizationPage.empty(1, List.of())));
            mockEmptyJdbiSources();

            StepVerifier.create(service.getRecentActivity(PROJECT_ID, 1, 10))
                    .assertNext(result -> {
                        assertThat(result.content()).hasSize(1);
                        assertThat(result.content().get(0).name()).isEqualTo("valid alert");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Empty project:")
    class EmptyProject {

        @Test
        void shouldReturnEmptyItemsWhenNoActivity() {
            when(experimentService.find(anyInt(), anyInt(), any()))
                    .thenReturn(Mono.just(Experiment.ExperimentPage.empty(1, List.of())));
            when(optimizationService.find(anyInt(), anyInt(), any()))
                    .thenReturn(Mono.just(Optimization.OptimizationPage.empty(1, List.of())));
            when(alertEventLogsDAO.findLogs(any())).thenReturn(Flux.empty());
            mockEmptyJdbiSources();

            StepVerifier.create(service.getRecentActivity(PROJECT_ID, 1, 10))
                    .assertNext(result -> assertThat(result.content()).isEmpty())
                    .verifyComplete();
        }
    }
}
