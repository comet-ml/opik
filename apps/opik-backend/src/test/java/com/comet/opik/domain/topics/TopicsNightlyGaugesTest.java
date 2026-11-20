package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.TopicsConfig;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("topics nightly gauges")
class TopicsNightlyGaugesTest {

    private static final String WORKSPACE_ID = "ws-1";
    private static final String QUIET_WORKSPACE_ID = "ws-2";
    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 15);
    private static final Instant NOW = NIGHT.atTime(6, 0).toInstant(ZoneOffset.UTC);

    private InMemoryMetricReader reader;
    private TopicsConfig config;
    private TopicProjectNightDAO dao;
    private TransactionTemplate template;
    private TopicsNightlyGauges gauges;

    @BeforeEach
    void setUp() {
        reader = InMemoryMetricReader.create();
        config = TestConfigUtils.loadConfigTest().getTopics();
        config.setEnabled(true);
        dao = mock(TopicProjectNightDAO.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicProjectNightDAO.class)).thenReturn(dao);
        template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        var workspaces = mock(TopicWorkspaceService.class);
        when(workspaces.enabledWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID, QUIET_WORKSPACE_ID));
        gauges = new TopicsNightlyGauges(config, template, workspaces, workspaceId -> "name of " + workspaceId,
                TopicsMetricsReader.meter(reader), Clock.fixed(NOW, ZoneOffset.UTC));
        gauges.begin();
    }

    @Test
    @DisplayName("a refresh publishes every enabled workspace, 0 unless counted, and the latest night's age; a read "
            + "that fails keeps them and counts")
    void refresh() {
        when(dao.lastNight(any())).thenReturn(NIGHT);
        when(dao.countStale(any(), anyInt()))
                .thenReturn(List.of(new TopicProjectNightDAO.WorkspaceCount(WORKSPACE_ID, 2)));
        when(dao.countStarved(any(), anyInt())).thenReturn(List.of());

        gauges.refresh();
        when(template.inTransaction(any(TxConfig.class), any())).thenThrow(new IllegalStateException("MySQL down"));
        gauges.refresh();

        assertThat(TopicsMetricsReader.points(reader, TopicsNightlyGauges.STALE_PROJECTS))
                .extracting(point -> point.get("workspace_id"), point -> point.get("workspace_name"),
                        TopicsMetricsReader.Point::value)
                .containsExactlyInAnyOrder(tuple(WORKSPACE_ID, "name of " + WORKSPACE_ID, 2.0),
                        tuple(QUIET_WORKSPACE_ID, "name of " + QUIET_WORKSPACE_ID, 0.0));
        assertThat(TopicsMetricsReader.points(reader, TopicsNightlyGauges.STARVED_PROJECTS))
                .extracting(TopicsMetricsReader.Point::value)
                .containsExactly(0.0, 0.0);
        assertThat(TopicsMetricsReader.points(reader, TopicsNightlyGauges.LAST_NIGHT_AGE))
                .extracting(TopicsMetricsReader.Point::value)
                .containsExactly(6 * 3_600.0);
        assertThat(TopicsMetricsReader.sum(reader, TopicsNightlyGauges.STATE_REFRESH_FAILURES)).isEqualTo(1);
    }

    @Test
    @DisplayName("before any night looked at a project, no last-night age is published; off, nothing is")
    void beforeTheFirstNightAndOff() {
        gauges.refresh();

        assertThat(TopicsMetricsReader.points(reader, TopicsNightlyGauges.LAST_NIGHT_AGE)).isEmpty();
        assertThat(TopicsMetricsReader.points(reader, TopicsNightlyGauges.STALE_PROJECTS)).hasSize(2);

        config.getNightly().setEnabled(false);

        assertThat(TopicsMetricsReader.points(reader, TopicsNightlyGauges.STALE_PROJECTS)).isEmpty();
        assertThat(TopicsMetricsReader.points(reader, TopicsNightlyGauges.STATE_AGE)).isEmpty();
    }
}
