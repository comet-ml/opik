package com.comet.opik.domain.topics;

import com.clickhouse.client.api.ServerException;
import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.HttpHeaders;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What containers cannot trip: the clock at the day's edges, the reads a disabled workspace skips, the error mapping
 * and the duration metric. The service is built by hand, so no cache sits between it and the mocks.
 */
@DisplayName("topics read service")
class TopicReadServiceTest {

    private static final String WORKSPACE_ID = "ws-1";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    private static final Instant NOW = TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC);
    private static final Instant FROM = TODAY.minusDays(29).atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final Instant TO = TODAY.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final String DURATION = "topics.api.duration_seconds";

    private final TopicsConfig config = TestConfigUtils.loadConfigTest().getTopics();
    private final UUID projectId = UUID.randomUUID();
    private final UUID lastRunId = UUID.randomUUID();

    private TopicsTestMeter meter;
    private TopicWorkspaceService workspaces;
    private TopicDAO topicDAO;
    private TopicRunDAO runDAO;
    private ProjectService projectService;
    private TopicWindowDAO windowDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private TransactionTemplate template;
    private TopicReadService service;

    @BeforeEach
    void setUp() {
        meter = new TopicsTestMeter();
        workspaces = mock(TopicWorkspaceService.class);
        topicDAO = mock(TopicDAO.class);
        runDAO = mock(TopicRunDAO.class);
        projectService = mock(ProjectService.class);
        windowDAO = mock(TopicWindowDAO.class);
        traceTopicsDAO = mock(TraceTopicsDAO.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicDAO.class)).thenReturn(topicDAO);
        when(handle.attach(TopicRunDAO.class)).thenReturn(runDAO);
        template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        when(workspaces.isEligible(WORKSPACE_ID)).thenReturn(true);
        when(topicDAO.findStates(WORKSPACE_ID, List.of(projectId))).thenReturn(List.of());
        service = service(NOW);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-03T00:00:00Z, 2026-11-03",
            "2026-11-03T23:59:59.999Z, 2026-11-03",
            "2026-11-02T23:59:59.999Z, 2026-11-02"})
    @DisplayName("today is the UTC date of the request, from its first to its last millisecond")
    void today(Instant now, LocalDate today) {
        when(workspaces.isEligible(WORKSPACE_ID)).thenReturn(false);

        var body = service(now).get(WORKSPACE_ID, projectId);

        assertThat(body.today()).isEqualTo(today);
        assertThat(body.windowFrom()).isEqualTo(today.minusDays(config.getBuild().getWindowDays() - 1L));
    }

    @Test
    @DisplayName("ready: the window's counts, read once over the window, and the duration by status")
    void ready() {
        var topic = topic();
        saved(List.of(topic));
        when(traceTopicsDAO.countByTopicAndDay(WORKSPACE_ID, projectId, FROM, TO,
                TopicReadService.MAX_EXECUTION_SECONDS)).thenReturn(Flux.just(
                        TopicDayCount.builder().topicId(topic.id()).day(TODAY).traces(5).build(),
                        TopicDayCount.builder().topicId(UUID.randomUUID()).day(TODAY).traces(2).build()));

        var body = service.get(WORKSPACE_ID, projectId);

        assertThat(body.status()).isEqualTo(ProjectTopics.Status.READY);
        assertThat(body.totalTraces()).isEqualTo(7L);
        assertThat(body.lastRunAt()).isEqualTo(NOW);
        // The newest run is read only when collecting.
        assertThat(body.attempt()).isNull();
        verify(runDAO, never()).findNewest(any(), any());
        assertThat(meter.points(DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("ready"));
        verify(projectService).get(projectId, WORKSPACE_ID);
    }

    @Test
    @DisplayName("disabled: a workspace not eligible reads no topics and calls no ClickHouse")
    void disabled() {
        when(workspaces.isEligible(WORKSPACE_ID)).thenReturn(false);

        var body = service.get(WORKSPACE_ID, projectId);

        assertThat(body.status()).isEqualTo(ProjectTopics.Status.DISABLED);
        assertThat(body.today()).isEqualTo(TODAY);
        assertThat(body.topics()).isEmpty();
        assertThat(body.attempt()).isNull();
        verifyNoInteractions(topicDAO, runDAO, windowDAO, traceTopicsDAO);
        assertThat(meter.points(DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("disabled"));
    }

    @ParameterizedTest(name = "a {1} limit (code {0}) is a 503 with Retry-After, status error: a retry can pass")
    @CsvSource({"159, time", "241, memory"})
    void timeOrMemoryLimit(int code, String limit) {
        readyWithCounts(Flux.error(new RuntimeException(new ServerException(code, limit + " limit", 500, "query-id"))));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, projectId))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, exception -> assertThat(
                        exception.getResponse().getHeaderString(HttpHeaders.RETRY_AFTER))
                        .isEqualTo(TopicsReadErrors.RETRY_AFTER_SECONDS));
        assertThat(meter.points(DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("error"));
    }

    @Test
    @DisplayName("the r2dbc driver's error, the v1 client's ClickHouseException, with a rows limit (158) is a 400 with "
            + "the too-large message, status error")
    void rowsLimitFromTheR2dbcDriver() {
        readyWithCounts(Flux.error(
                new com.clickhouse.client.ClickHouseException(158, "Limit for rows (controlled by "
                        + "'max_rows_to_read' setting) exceeded", null)));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, projectId))
                .isInstanceOfSatisfying(BadRequestException.class, exception -> {
                    assertThat(exception.getResponse().getHeaderString(HttpHeaders.RETRY_AFTER)).isNull();
                    assertThat(exception.getResponse().getEntity()).isEqualTo(
                            new ErrorMessage(List.of(TopicReadService.TOO_LARGE_MESSAGE)));
                });
        assertThat(meter.points(DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("error"));
    }

    @ParameterizedTest
    @ValueSource(ints = {307, 396})
    @DisplayName("a bytes limit (307) or a rows-or-bytes limit (396) is a 400 too")
    void bytesLimit(int code) {
        readyWithCounts(Flux.error(new RuntimeException(new ServerException(code, "Limit exceeded", 500, "query-id"))));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, projectId))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("the client timeout is a 503 too")
    void clientTimeout() {
        readyWithCounts(Flux.error(new TimeoutException("Did not observe any item")));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, projectId))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(meter.points(DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("error"));
    }

    @Test
    @DisplayName("another ClickHouse error stays as it is, status error")
    void otherError() {
        readyWithCounts(Flux.error(new RuntimeException(new ServerException(62, "Syntax error", 400, "query-id"))));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, projectId))
                .isNotInstanceOf(ServiceUnavailableException.class)
                .hasRootCauseInstanceOf(ServerException.class);
        assertThat(meter.points(DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("error"));
    }

    @Test
    @DisplayName("a project of another workspace is not found before any read, and records no duration")
    void notFound() {
        when(projectService.get(projectId, WORKSPACE_ID)).thenThrow(new NotFoundException("Project not found"));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, projectId))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(workspaces, topicDAO, traceTopicsDAO);
        verify(windowDAO, never()).countByProject(any(), any(), any(), any(), anyLong());
        assertThat(meter.points(DURATION)).isEmpty();
    }

    @Test
    @DisplayName("filter scope: no topic filter reads nothing; topics off, a workspace not eligible or no live topic "
            + "is empty; else the live ids and the guards of topics.filter")
    void filterScope() {
        var topicFilter = List.of(TraceFilter.builder()
                .field(TraceField.TOPIC_ID).operator(Operator.EQUAL).value(UUID.randomUUID().toString()).build());
        var nameFilter = List.of(TraceFilter.builder()
                .field(TraceField.NAME).operator(Operator.EQUAL).value("name").build());
        var topic = topic();
        when(topicDAO.findLive(WORKSPACE_ID, projectId)).thenReturn(List.of(topic));

        config.setEnabled(true);
        assertThat(filterScope(null)).isEmpty();
        assertThat(filterScope(nameFilter)).isEmpty();
        verifyNoInteractions(workspaces, topicDAO);

        config.setEnabled(false);
        assertThat(filterScope(topicFilter)).isEmpty();
        verifyNoInteractions(workspaces, topicDAO);

        config.setEnabled(true);
        when(workspaces.isEligible(WORKSPACE_ID)).thenReturn(false);
        assertThat(filterScope(topicFilter)).isEmpty();
        verifyNoInteractions(topicDAO);

        when(workspaces.isEligible(WORKSPACE_ID)).thenReturn(true);
        assertThat(filterScope(topicFilter)).hasValueSatisfying(scope -> {
            assertThat(scope.liveIds()).containsExactly(topic.id().toString());
            assertThat(scope.maxExecutionSeconds()).isEqualTo(30L);
            assertThat(scope.maxMemoryUsage()).isEqualTo(4L * 1024 * 1024 * 1024);
        });
        // No state read: the filter needs only the live topics.
        verify(topicDAO, never()).findStates(any(), any());

        when(topicDAO.findLive(WORKSPACE_ID, projectId)).thenReturn(List.of());
        assertThat(filterScope(topicFilter)).isEmpty();
    }

    private Optional<TopicFilterScope> filterScope(List<TraceFilter> filters) {
        return service.filterScope(filters, projectId)
                .contextWrite(Context.of(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block();
    }

    private TopicReadService service(Instant now) {
        return new TopicReadService(config, template, workspaces, projectService, windowDAO, traceTopicsDAO,
                new TopicsMetrics(meter.meter), Clock.fixed(now, ZoneOffset.UTC));
    }

    /** The project's state, last saved by {@code lastRunId}, and its live topics. */
    private void saved(List<Topic> live) {
        when(topicDAO.findStates(WORKSPACE_ID, List.of(projectId))).thenReturn(List.of(TopicProjectState.builder()
                .projectId(projectId)
                .workspaceId(WORKSPACE_ID)
                .rebuilds(1)
                .firstBuiltAt(TODAY.minusDays(10).atStartOfDay().toInstant(ZoneOffset.UTC))
                .lastRunId(lastRunId)
                .lastSuccessAt(NOW)
                .windowUntil(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC))
                .build()));
        when(topicDAO.findLive(WORKSPACE_ID, projectId)).thenReturn(live);
    }

    private void readyWithCounts(Flux<TopicDayCount> counts) {
        saved(List.of(topic()));
        when(traceTopicsDAO.countByTopicAndDay(eq(WORKSPACE_ID), eq(projectId), any(), any(), anyLong()))
                .thenReturn(counts);
    }

    private Topic topic() {
        var id = UUID.randomUUID();
        return Topic.builder()
                .id(id)
                .workspaceId(WORKSPACE_ID)
                .projectId(projectId)
                .name("Topic " + id)
                .definition("Definition of " + id)
                .born(TODAY.minusDays(10))
                .build();
    }
}
