package com.comet.opik.domain;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.LogItem;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.RecentActivity.ActivityType;
import com.comet.opik.domain.alerts.AlertEventLogsDAO;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecentActivityServiceTest {

    private static final String WORKSPACE_ID = "test-workspace";
    private static final UUID PROJECT_ID = UUID.randomUUID();

    @Mock
    private ExperimentService experimentService;
    @Mock
    private OptimizationService optimizationService;
    @Mock
    private AlertEventLogsDAO alertEventLogsDAO;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private Provider<RequestContext> requestContextProvider;
    @Mock
    private RequestContext requestContext;

    private RecentActivityService service;

    @BeforeEach
    void setUp() {
        when(requestContextProvider.get()).thenReturn(requestContext);
        when(requestContext.getWorkspaceId()).thenReturn(WORKSPACE_ID);

        service = new RecentActivityService(
                experimentService,
                optimizationService,
                alertEventLogsDAO,
                transactionTemplate,
                requestContextProvider);
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

            var experiment = Experiment.builder()
                    .id(UUID.randomUUID()).datasetId(UUID.randomUUID())
                    .name("exp-1").createdAt(middle).build();
            var experimentPage = new Experiment.ExperimentPage(1, 1, 1, List.of(experiment), List.of());
            when(experimentService.find(anyInt(), anyInt(), any())).thenReturn(Mono.just(experimentPage));

            var optimization = Optimization.builder()
                    .id(UUID.randomUUID()).datasetName("opt-dataset").createdAt(newest).build();
            var optimizationPage = new Optimization.OptimizationPage(1, 1, 1, List.of(optimization), List.of());
            when(optimizationService.find(anyInt(), anyInt(), any())).thenReturn(Mono.just(optimizationPage));

            var alertId = UUID.randomUUID();
            var alertLog = LogItem.builder()
                    .timestamp(oldest).message("alert fired")
                    .markers(Map.of(UserLog.ALERT_ID, alertId.toString()))
                    .build();
            when(alertEventLogsDAO.findLogs(any())).thenReturn(Flux.just(alertLog));

            mockEmptyJdbiSources();

            StepVerifier.create(service.getRecentActivity(PROJECT_ID, 10))
                    .assertNext(result -> {
                        assertThat(result.items()).hasSize(3);
                        assertThat(result.items().get(0).type()).isEqualTo(ActivityType.OPTIMIZATION);
                        assertThat(result.items().get(1).type()).isEqualTo(ActivityType.EXPERIMENT);
                        assertThat(result.items().get(2).type()).isEqualTo(ActivityType.ALERT_EVENT);
                    })
                    .verifyComplete();
        }

        @Test
        void shouldLimitResultsToRequestedSize() {
            var now = Instant.now();

            var experiments = List.of(
                    Experiment.builder().id(UUID.randomUUID()).datasetId(UUID.randomUUID())
                            .name("exp-1").createdAt(now.minusSeconds(1)).build(),
                    Experiment.builder().id(UUID.randomUUID()).datasetId(UUID.randomUUID())
                            .name("exp-2").createdAt(now.minusSeconds(2)).build(),
                    Experiment.builder().id(UUID.randomUUID()).datasetId(UUID.randomUUID())
                            .name("exp-3").createdAt(now.minusSeconds(3)).build());
            var experimentPage = new Experiment.ExperimentPage(1, 3, 3, experiments, List.of());
            when(experimentService.find(anyInt(), anyInt(), any())).thenReturn(Mono.just(experimentPage));

            when(optimizationService.find(anyInt(), anyInt(), any()))
                    .thenReturn(Mono.just(Optimization.OptimizationPage.empty(1, List.of())));
            when(alertEventLogsDAO.findLogs(any())).thenReturn(Flux.empty());
            mockEmptyJdbiSources();

            StepVerifier.create(service.getRecentActivity(PROJECT_ID, 2))
                    .assertNext(result -> assertThat(result.items()).hasSize(2))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Graceful degradation:")
    class GracefulDegradation {

        @Test
        void shouldReturnPartialResultsWhenOneSourceFails() {
            var optimization = Optimization.builder()
                    .id(UUID.randomUUID()).datasetName("opt-dataset")
                    .createdAt(Instant.now()).build();
            var optimizationPage = new Optimization.OptimizationPage(1, 1, 1, List.of(optimization), List.of());
            when(optimizationService.find(anyInt(), anyInt(), any())).thenReturn(Mono.just(optimizationPage));

            when(experimentService.find(anyInt(), anyInt(), any()))
                    .thenReturn(Mono.error(new RuntimeException("ClickHouse down")));
            when(alertEventLogsDAO.findLogs(any())).thenReturn(Flux.empty());
            mockEmptyJdbiSources();

            StepVerifier.create(service.getRecentActivity(PROJECT_ID, 10))
                    .assertNext(result -> {
                        assertThat(result.items()).hasSize(1);
                        assertThat(result.items().get(0).type()).isEqualTo(ActivityType.OPTIMIZATION);
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

            StepVerifier.create(service.getRecentActivity(PROJECT_ID, 10))
                    .assertNext(result -> assertThat(result.items()).isEmpty())
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

            StepVerifier.create(service.getRecentActivity(PROJECT_ID, 10))
                    .assertNext(result -> {
                        assertThat(result.items()).hasSize(1);
                        assertThat(result.items().get(0).name()).isEqualTo("valid alert");
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

            StepVerifier.create(service.getRecentActivity(PROJECT_ID, 10))
                    .assertNext(result -> assertThat(result.items()).isEmpty())
                    .verifyComplete();
        }
    }
}
