package com.comet.opik.domain.topics;

import com.clickhouse.client.api.ServerException;
import com.comet.opik.api.ProjectTopics;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.HttpHeaders;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
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

@DisplayName("topics read service")
class TopicReadServiceTest {

    private static final String WORKSPACE_ID = "ws-1";
    private static final String WORKSPACE_NAME = "Acme";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    private static final Instant NOW = TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC);
    private static final Instant FROM = TODAY.minusDays(29).atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final Instant TO = TODAY.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final String DURATION = "topics.api.duration_seconds";
    private static final String STALE = "topics.api.stale_traces";

    private final UUID projectId = UUID.randomUUID();

    private TopicsTestMeter meter;
    private TopicWorkspaceDAO workspaceDAO;
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
        workspaceDAO = mock(TopicWorkspaceDAO.class);
        topicDAO = mock(TopicDAO.class);
        runDAO = mock(TopicRunDAO.class);
        projectService = mock(ProjectService.class);
        windowDAO = mock(TopicWindowDAO.class);
        traceTopicsDAO = mock(TraceTopicsDAO.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicWorkspaceDAO.class)).thenReturn(workspaceDAO);
        when(handle.attach(TopicDAO.class)).thenReturn(topicDAO);
        when(handle.attach(TopicRunDAO.class)).thenReturn(runDAO);
        template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        when(workspaceDAO.isEligible(WORKSPACE_ID)).thenReturn(true);
        when(topicDAO.findState(WORKSPACE_ID, projectId)).thenReturn(Optional.empty());
        service = service(NOW);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-03T00:00:00Z, 2026-11-03",
            "2026-11-03T23:59:59.999Z, 2026-11-03",
            "2026-11-02T23:59:59.999Z, 2026-11-02"})
    @DisplayName("today is the UTC date of the request, from its first to its last millisecond")
    void today(Instant now, LocalDate today) {
        when(workspaceDAO.isEligible(WORKSPACE_ID)).thenReturn(false);

        var body = service(now).get(WORKSPACE_ID, WORKSPACE_NAME, projectId);

        assertThat(body.today()).isEqualTo(today);
        assertThat(body.windowFrom()).isEqualTo(today.minusDays(29));
    }

    @Test
    @DisplayName("ready: counts of the saved runs' rows, the duration by status and the stale traces with the "
            + "request's workspace name")
    void ready() {
        var topic = topic();
        var savedRuns = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(topicDAO.findLive(WORKSPACE_ID, projectId)).thenReturn(List.of(topic));
        when(runDAO.findSavedRunIds(WORKSPACE_ID, projectId)).thenReturn(savedRuns);
        when(traceTopicsDAO.countByTopicAndDay(WORKSPACE_ID, projectId, FROM, TO, savedRuns,
                TopicReadService.MAX_EXECUTION_SECONDS)).thenReturn(Flux.just(
                        TopicDayCount.builder().topicId(topic.id()).day(TODAY).traces(5).build(),
                        TopicDayCount.builder().topicId(UUID.randomUUID()).day(TODAY).traces(2).build()));

        var body = service.get(WORKSPACE_ID, WORKSPACE_NAME, projectId);

        assertThat(body.status()).isEqualTo(ProjectTopics.Status.READY);
        assertThat(body.totalTraces()).isEqualTo(7L);
        assertThat(body.other().stale()).isEqualTo(2);
        assertThat(meter.points(DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("ready"));
        assertThat(meter.points(STALE)).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(2);
            assertThat(point.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(point.get("workspace_name")).isEqualTo(WORKSPACE_NAME);
        });
        verify(projectService).get(projectId, WORKSPACE_ID);
    }

    @Test
    @DisplayName("ready with no stale traces adds nothing to the stale counter")
    void readyWithoutStale() {
        var topic = topic();
        when(topicDAO.findLive(WORKSPACE_ID, projectId)).thenReturn(List.of(topic));
        when(runDAO.findSavedRunIds(WORKSPACE_ID, projectId)).thenReturn(List.of());
        when(traceTopicsDAO.countByTopicAndDay(eq(WORKSPACE_ID), eq(projectId), any(), any(), any(), anyLong()))
                .thenReturn(Flux.just(TopicDayCount.builder().topicId(topic.id()).day(TODAY).traces(1).build()));

        service.get(WORKSPACE_ID, WORKSPACE_NAME, projectId);

        assertThat(meter.points(STALE)).isEmpty();
    }

    @Test
    @DisplayName("collecting: no live topic; the window's vectors (0 with no row) and the first build's minimum, no "
            + "counts read")
    void collecting() {
        when(topicDAO.findLive(WORKSPACE_ID, projectId)).thenReturn(List.of());
        when(windowDAO.countByProject(WORKSPACE_ID, projectId, FROM, TO, TopicReadService.MAX_EXECUTION_SECONDS))
                .thenReturn(Flux.empty());

        var body = service.get(WORKSPACE_ID, WORKSPACE_NAME, projectId);

        assertThat(body.status()).isEqualTo(ProjectTopics.Status.COLLECTING);
        assertThat(body.vectorsInWindow()).isZero();
        assertThat(body.minVectors()).isEqualTo((long) new TopicsConfig().getNightly().getMinVectors());
        assertThat(body.topics()).isEmpty();
        assertThat(body.totalTraces()).isNull();
        assertThat(body.other()).isNull();
        verifyNoInteractions(traceTopicsDAO, runDAO);
        assertThat(meter.points(DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("collecting"));
    }

    @Test
    @DisplayName("disabled: a workspace not eligible reads no topics and calls no ClickHouse, even with live topics")
    void disabled() {
        when(workspaceDAO.isEligible(WORKSPACE_ID)).thenReturn(false);
        when(topicDAO.findLive(WORKSPACE_ID, projectId)).thenReturn(List.of(topic()));

        var body = service.get(WORKSPACE_ID, WORKSPACE_NAME, projectId);

        assertThat(body.status()).isEqualTo(ProjectTopics.Status.DISABLED);
        assertThat(body.today()).isEqualTo(TODAY);
        assertThat(body.topics()).isEmpty();
        verifyNoInteractions(topicDAO, runDAO, windowDAO, traceTopicsDAO);
        assertThat(meter.points(DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("disabled"));
    }

    @Test
    @DisplayName("a ClickHouse limit error (code 159) is a 503 with Retry-After, status error")
    void clickHouseLimit() {
        readyWithCounts(
                Flux.error(new RuntimeException(new ServerException(159, "Timeout exceeded", 500, "query-id"))));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, WORKSPACE_NAME, projectId))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, exception -> assertThat(
                        exception.getResponse().getHeaderString(HttpHeaders.RETRY_AFTER))
                        .isEqualTo(TopicReadService.RETRY_AFTER_SECONDS));
        assertThat(meter.points(DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("error"));
    }

    @Test
    @DisplayName("the client timeout is a 503 too")
    void clientTimeout() {
        readyWithCounts(Flux.error(new TimeoutException("Did not observe any item")));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, WORKSPACE_NAME, projectId))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(meter.points(DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("error"));
    }

    @Test
    @DisplayName("another ClickHouse error stays as it is, status error")
    void otherError() {
        readyWithCounts(Flux.error(new RuntimeException(new ServerException(62, "Syntax error", 400, "query-id"))));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, WORKSPACE_NAME, projectId))
                .isNotInstanceOf(ServiceUnavailableException.class)
                .hasRootCauseInstanceOf(ServerException.class);
        assertThat(meter.points(DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("error"));
    }

    @Test
    @DisplayName("a project of another workspace is not found before any read, and records no duration")
    void notFound() {
        when(projectService.get(projectId, WORKSPACE_ID)).thenThrow(new NotFoundException("Project not found"));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, WORKSPACE_NAME, projectId))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(workspaceDAO, topicDAO, traceTopicsDAO);
        verify(windowDAO, never()).countByProject(any(), any(), any(), any(), anyLong());
        assertThat(meter.points(DURATION)).isEmpty();
    }

    private TopicReadService service(Instant now) {
        return new TopicReadService(new TopicsConfig(), template, projectService, windowDAO, traceTopicsDAO,
                new TopicsMetrics(meter.meter), Clock.fixed(now, ZoneOffset.UTC));
    }

    private void readyWithCounts(Flux<TopicDayCount> counts) {
        when(topicDAO.findLive(WORKSPACE_ID, projectId)).thenReturn(List.of(topic()));
        when(runDAO.findSavedRunIds(WORKSPACE_ID, projectId)).thenReturn(List.of());
        when(traceTopicsDAO.countByTopicAndDay(eq(WORKSPACE_ID), eq(projectId), any(), any(), any(), anyLong()))
                .thenReturn(counts);
    }

    private Topic topic() {
        return Topic.builder()
                .id(UUID.randomUUID())
                .workspaceId(WORKSPACE_ID)
                .projectId(projectId)
                .name("Refund requests")
                .definition("Users ask to get their money back.")
                .born(TODAY.minusDays(10))
                .build();
    }
}
