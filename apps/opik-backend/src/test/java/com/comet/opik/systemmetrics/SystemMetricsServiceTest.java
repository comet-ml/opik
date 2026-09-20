package com.comet.opik.systemmetrics;

import com.comet.opik.infrastructure.SystemMetricsConfig;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemMetricsServiceTest {

    private static final String WORKSPACE_ID = "workspace-id";
    private static final UUID PROJECT_ID = UUID.randomUUID();

    @Mock
    private SystemMetricsDAO dao;

    private SystemMetricsConfig config;
    private SystemMetricsService service;

    @BeforeEach
    void setUp() {
        config = new SystemMetricsConfig();
        config.setEnabled(true);
        config.setMaxBatchSize(100);
        config.setMaxQueryPoints(1_000);
        config.setMaxQueryRange(Duration.hours(24));
        service = new SystemMetricsService(dao, config);
    }

    @Test
    void ingestNormalizesOptionalFieldsAndStoresTenantFromRequestContext() {
        var point = new SystemMetricPoint(
                UUID.randomUUID(),
                "agent-service",
                "instance-1",
                null,
                "agent.process.memory",
                "By",
                Instant.now(),
                1024.0,
                null);
        when(dao.insertBatch(eq(WORKSPACE_ID), eq(PROJECT_ID), any())).thenReturn(Mono.just(1L));

        var accepted = service.ingest(WORKSPACE_ID, PROJECT_ID, new SystemMetricBatch(List.of(point)));

        assertThat(accepted).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        var points = (ArgumentCaptor<List<SystemMetricPoint>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(dao).insertBatch(eq(WORKSPACE_ID), eq(PROJECT_ID), points.capture());
        assertThat(points.getValue().getFirst().agentId()).isEmpty();
        assertThat(points.getValue().getFirst().attributes()).isEmpty();
    }

    @Test
    void ingestRejectsNonFiniteValues() {
        var point = new SystemMetricPoint(
                UUID.randomUUID(),
                "agent-service",
                "instance-1",
                "assistant",
                "agent.process.memory",
                "By",
                Instant.now(),
                Double.NaN,
                Map.of());

        assertThatThrownBy(() -> service.ingest(
                WORKSPACE_ID, PROJECT_ID, new SystemMetricBatch(List.of(point))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("finite");
    }

    @Test
    void queryReturnsOrderedStoredPointsAndUnit() {
        var from = Instant.parse("2026-09-20T00:00:00Z");
        var to = from.plusSeconds(60);
        when(dao.find(WORKSPACE_ID, PROJECT_ID, "instance-1", "agent.process.memory", from, to, 1_001))
                .thenReturn(Mono.just(List.of(
                        new SystemMetricsDAO.StoredMetricPoint(
                                from.plusSeconds(15), 1024.0, "By", Map.of("state", "rss")),
                        new SystemMetricsDAO.StoredMetricPoint(
                                from.plusSeconds(30), 2048.0, "By", Map.of("state", "rss")))));

        var result = service.query(
                WORKSPACE_ID, PROJECT_ID, "instance-1", "agent.process.memory", from, to);

        assertThat(result.unit()).isEqualTo("By");
        assertThat(result.points()).extracting(SystemMetricSample::value).containsExactly(1024.0, 2048.0);
    }

    @Test
    void queryRejectsRangeBeyondConfiguredMaximum() {
        var from = Instant.parse("2026-09-18T00:00:00Z");
        var to = from.plusSeconds(86_401);

        assertThatThrownBy(() -> service.query(
                WORKSPACE_ID, PROJECT_ID, "instance-1", "agent.process.memory", from, to))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("maximum");
    }

    @Test
    void listInstancesReturnsRecentlyActiveAgents() {
        when(dao.findInstances(eq(WORKSPACE_ID), eq(PROJECT_ID), any(), eq(1_000)))
                .thenReturn(Mono.just(List.of(new SystemMetricInstance(
                        "instance-1", "agent-service", "assistant", Instant.now()))));

        var result = service.listInstances(WORKSPACE_ID, PROJECT_ID);

        assertThat(result.instances()).extracting(SystemMetricInstance::serviceInstanceId)
                .containsExactly("instance-1");
    }

    @Test
    void disabledFeatureRejectsWritesAndReads() {
        config.setEnabled(false);
        var now = Instant.now();

        assertThatThrownBy(() -> service.query(
                WORKSPACE_ID, PROJECT_ID, "instance-1", "agent.process.memory", now.minusSeconds(10), now))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("disabled");
    }
}
