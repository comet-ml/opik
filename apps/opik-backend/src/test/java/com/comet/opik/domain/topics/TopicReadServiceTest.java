package com.comet.opik.domain.topics;

import com.clickhouse.client.ClickHouseException;
import com.clickhouse.client.api.ServerException;
import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsStage;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.RandomStringUtils;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
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

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final String WORKSPACE_ID = "ws-1";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    private static final Instant NOW = TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC);
    private static final Instant FROM = TODAY.minusDays(29).atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final Instant TO = TODAY.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final String DURATION = "topics.api.duration_seconds";

    private final TopicsConfig config = TestConfigUtils.loadConfigTest().getTopics();
    private final UUID projectId = UUID.randomUUID();
    private final UUID lastRunId = UUID.randomUUID();

    private InMemoryMetricReader metricReader;
    private TopicWorkspaceService workspaces;
    private TopicDAO topicDAO;
    private TopicProjectDAO projectDAO;
    private ProjectService projectService;
    private TopicWindowDAO windowDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private TransactionTemplate template;
    private TopicReadService service;

    @BeforeEach
    void setUp() {
        metricReader = InMemoryMetricReader.create();
        workspaces = mock(TopicWorkspaceService.class);
        topicDAO = mock(TopicDAO.class);
        projectDAO = mock(TopicProjectDAO.class);
        projectService = mock(ProjectService.class);
        windowDAO = mock(TopicWindowDAO.class);
        traceTopicsDAO = mock(TraceTopicsDAO.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicDAO.class)).thenReturn(topicDAO);
        when(handle.attach(TopicProjectDAO.class)).thenReturn(projectDAO);
        template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        when(workspaces.isEnabled(WORKSPACE_ID)).thenReturn(true);
        when(projectDAO.findProjects(WORKSPACE_ID, List.of(projectId))).thenReturn(List.of());
        service = service(NOW);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-03T00:00:00Z, 2026-11-03",
            "2026-11-03T23:59:59.999Z, 2026-11-03",
            "2026-11-02T23:59:59.999Z, 2026-11-02"})
    @DisplayName("today is the UTC date of the request, from its first to its last millisecond")
    void today(Instant now, LocalDate today) {
        when(workspaces.isEnabled(WORKSPACE_ID)).thenReturn(false);

        var body = service(now).get(WORKSPACE_ID, projectId);

        assertThat(body.today()).isEqualTo(today);
        assertThat(body.windowFrom()).isEqualTo(today.minusDays(config.getRun().getWindowDays() - 1L));
    }

    @Test
    @DisplayName("ready: the window's counts, read once over the window, and the duration by status")
    void ready() {
        var topic = topic();
        saved(List.of(topic));
        when(traceTopicsDAO.countByTopicAndDay(WORKSPACE_ID, projectId, FROM, TO)).thenReturn(Flux.just(
                TopicDayCount.builder().topicId(topic.id()).day(TODAY).traces(5).build(),
                TopicDayCount.builder().topicId(UUID.randomUUID()).day(TODAY).traces(2).build()));

        var body = service.get(WORKSPACE_ID, projectId);

        assertThat(body.status()).isEqualTo(ProjectTopics.Status.READY);
        assertThat(body.totalTraces()).isEqualTo(7L);
        assertThat(body.lastRunAt()).isEqualTo(NOW);
        assertThat(body.attempt()).isNull();
        assertThat(TopicsMetricsReader.points(metricReader, DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("ready"));
        verify(projectService).get(projectId, WORKSPACE_ID);
    }

    @Test
    @DisplayName("disabled: a workspace not eligible reads no topics and calls no ClickHouse")
    void disabled() {
        when(workspaces.isEnabled(WORKSPACE_ID)).thenReturn(false);

        var body = service.get(WORKSPACE_ID, projectId);

        assertThat(body.status()).isEqualTo(ProjectTopics.Status.DISABLED);
        assertThat(body.today()).isEqualTo(TODAY);
        assertThat(body.topics()).isEmpty();
        assertThat(body.attempt()).isNull();
        verifyNoInteractions(topicDAO, windowDAO, traceTopicsDAO);
        assertThat(TopicsMetricsReader.points(metricReader, DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("disabled"));
    }

    static Stream<Arguments> guardTrips() {
        return Stream.of(
                arguments("the r2dbc driver's error, the v1 client's ClickHouseException, with a rows limit (158)",
                        new ClickHouseException(158, "Limit for rows (controlled by "
                                + "'max_rows_to_read' setting) exceeded", null)),
                arguments("a memory limit (241)",
                        new RuntimeException(new ClickHouseException(241, "memory limit", null))),
                arguments("a bytes limit (307)",
                        new RuntimeException(new ClickHouseException(307, "Limit exceeded", null))),
                arguments("a rows-or-bytes limit (396)",
                        new RuntimeException(new ClickHouseException(396, "Limit exceeded", null))));
    }

    @ParameterizedTest(name = "{0} of the ready counts is status too_large: a retry fails the same way")
    @MethodSource("guardTrips")
    void readyGuardTripIsTooLarge(String name, Throwable error) {
        readyWithCounts(Flux.error(error));

        var body = service.get(WORKSPACE_ID, projectId);

        assertThat(body.status()).isEqualTo(ProjectTopics.Status.TOO_LARGE);
        assertThat(body.today()).isEqualTo(TODAY);
        assertThat(body.topics()).isEmpty();
        assertThat(TopicsMetricsReader.points(metricReader, DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("too_large"));
    }

    @Test
    @DisplayName("a guard trip of the collecting count is status too_large, without the collecting fields")
    void collectingGuardTripIsTooLarge() {
        when(windowDAO.countByProject(eq(WORKSPACE_ID), any(), any(), any(), any(), anyLong()))
                .thenReturn(Flux.error(new RuntimeException(new ClickHouseException(241, "memory limit", null))));

        var body = service.get(WORKSPACE_ID, projectId);

        assertThat(body.status()).isEqualTo(ProjectTopics.Status.TOO_LARGE);
        assertThat(body.vectorsInWindow()).isNull();
    }

    static Stream<Arguments> busy() {
        return Stream.of(
                arguments("a time limit (159)", new RuntimeException(
                        new ClickHouseException(159, "time limit", null))),
                arguments("the spent budget", new TopicsReadErrors.BudgetExceededException("spent")),
                arguments("the client timeout", new TimeoutException("Did not observe any item")));
    }

    @ParameterizedTest(name = "{0} is a 503 with Retry-After, status error")
    @MethodSource("busy")
    void busyIs503WithRetryAfter(String name, Throwable error) {
        readyWithCounts(Flux.error(error));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, projectId))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, exception -> assertThat(
                        exception.getResponse().getHeaderString(HttpHeaders.RETRY_AFTER))
                        .isEqualTo(TopicsReadErrors.RETRY_AFTER_SECONDS));
        assertThat(TopicsMetricsReader.points(metricReader, DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("error"));
    }

    @Test
    @DisplayName("another ClickHouse error stays as it is, status error")
    void otherError() {
        readyWithCounts(Flux.error(new RuntimeException(new ServerException(62, "Syntax error", 400, "query-id"))));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, projectId))
                .isNotInstanceOf(ServiceUnavailableException.class)
                .hasRootCauseInstanceOf(ServerException.class);
        assertThat(TopicsMetricsReader.points(metricReader, DURATION)).singleElement()
                .satisfies(point -> assertThat(point.get("status")).isEqualTo("error"));
    }

    @Test
    @DisplayName("a project of another workspace is not found before any read, and records no duration")
    void notFound() {
        when(projectService.get(projectId, WORKSPACE_ID)).thenThrow(new NotFoundException("Project not found"));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, projectId))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(workspaces, topicDAO, traceTopicsDAO);
        verify(windowDAO, never()).countByProject(any(), any(), any(), any(), any(), anyLong());
        assertThat(TopicsMetricsReader.points(metricReader, DURATION)).isEmpty();
    }

    private TopicReadService service(Instant now) {
        return new TopicReadService(config, template, workspaces, projectService, windowDAO, traceTopicsDAO,
                TopicsMetricsReader.metrics(metricReader), Clock.fixed(now, ZoneOffset.UTC));
    }

    /** The project's row, its list saved by {@code lastRunId}, and its live topics. */
    private void saved(List<Topic> live) {
        when(projectDAO.findProjects(WORKSPACE_ID, List.of(projectId))).thenReturn(List.of(TopicProject.builder()
                .projectId(projectId)
                .workspaceId(WORKSPACE_ID)
                .savedRuns(1)
                .firstBuiltAt(TODAY.minusDays(10).atStartOfDay().toInstant(ZoneOffset.UTC))
                .listRunId(lastRunId)
                .lastSuccessAt(NOW)
                .windowUntil(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC))
                .build()));
        when(topicDAO.findByProject(WORKSPACE_ID, projectId)).thenReturn(live);
    }

    private void readyWithCounts(Flux<TopicDayCount> counts) {
        saved(List.of(topic()));
        when(traceTopicsDAO.countByTopicAndDay(eq(WORKSPACE_ID), eq(projectId), any(), any()))
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
                .vectors(new PrototypeVectors(List.of(new float[TopicEmbedding.DIMENSIONS])))
                .build();
    }

    @Test
    @DisplayName("filter scope: no topic filter reads nothing; a workspace below the ui stage or no live topic is "
            + "empty; else the live ids and the guards of topics.filter")
    void filterScope() {
        var topicFilter = List.of(TraceFilter.builder()
                .field(TraceField.TOPIC_ID).operator(Operator.EQUAL).value(ID_GENERATOR.generateId().toString())
                .build());
        var nameFilter = List.of(TraceFilter.builder()
                .field(TraceField.NAME).operator(Operator.EQUAL).value(RandomStringUtils.secure().nextAlphanumeric(10))
                .build());
        var topic = topic();
        when(topicDAO.findByProject(WORKSPACE_ID, projectId)).thenReturn(List.of(topic));

        assertThat(filterScope(null)).isEmpty();
        assertThat(filterScope(nameFilter)).isEmpty();
        verifyNoInteractions(workspaces, topicDAO);

        when(workspaces.reaches(WORKSPACE_ID, TopicsStage.UI)).thenReturn(false);
        assertThat(filterScope(topicFilter)).isEmpty();
        verifyNoInteractions(topicDAO);

        when(workspaces.reaches(WORKSPACE_ID, TopicsStage.UI)).thenReturn(true);
        assertThat(filterScope(topicFilter)).contains(TopicFilterScope.builder()
                .liveIds(List.of(topic.id()))
                .maxExecutionSeconds(30L)
                .maxMemoryUsage(4L * 1024 * 1024 * 1024)
                .build());
        // No project row read: the filter needs only the live topics.
        verify(projectDAO, never()).findProjects(any(), any());

        when(topicDAO.findByProject(WORKSPACE_ID, projectId)).thenReturn(List.of());
        assertThat(filterScope(topicFilter)).isEmpty();
    }

    private Optional<TopicFilterScope> filterScope(List<TraceFilter> filters) {
        return service.filterScope(filters, projectId)
                .contextWrite(Context.of(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .blockOptional();
    }

}
