package com.comet.opik.domain.topics;

import com.clickhouse.client.api.ServerException;
import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.db.ClickHouseQueryLimits;
import com.google.common.base.Throwables;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * The topics read API: a project's topics over the last 30 UTC days, in one of three states.
 * <ul>
 * <li>{@code disabled}: the workspace is not eligible for topics (not opted in to topics or to ai-labels); no topics
 * are read and ClickHouse is not called, also when the project still has topics from before an opt-out.</li>
 * <li>{@code collecting}: eligible, no live topic; the body says how many traces with a vector the window has and how
 * many the first build needs.</li>
 * <li>{@code ready}: eligible with live topics; {@link TopicsCounts} folds the counts of
 * {@link TraceTopicsDAO#countByTopicAndDay}, and the counts of the project's last two saved runs into
 * {@code run_change}.</li>
 * </ul>
 * MySQL is read first, in one read-only transaction, then ClickHouse, with no lock: a night that saves in between makes
 * some of the request's rows stale, which count as Other. A ClickHouse read that trips a query limit or the client
 * timeout is a 503 with {@code Retry-After}.
 */
@Slf4j
@Singleton
public class TopicReadService {

    static final long MAX_EXECUTION_SECONDS = 10;
    static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(MAX_EXECUTION_SECONDS + 1);
    static final String RETRY_AFTER_SECONDS = "5";

    private final TopicsConfig config;
    private final TransactionTemplate template;
    private final ProjectService projectService;
    private final TopicWindowDAO windowDAO;
    private final TraceTopicsDAO traceTopicsDAO;
    private final TopicsMetrics metrics;
    private final Clock clock;

    @Inject
    public TopicReadService(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull ProjectService projectService, @NonNull TopicWindowDAO windowDAO,
            @NonNull TraceTopicsDAO traceTopicsDAO, @NonNull TopicsMetrics metrics) {
        this(config, template, projectService, windowDAO, traceTopicsDAO, metrics, Clock.systemUTC());
    }

    TopicReadService(@NonNull TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull ProjectService projectService, @NonNull TopicWindowDAO windowDAO,
            @NonNull TraceTopicsDAO traceTopicsDAO, @NonNull TopicsMetrics metrics, @NonNull Clock clock) {
        this.config = config;
        this.template = template;
        this.projectService = projectService;
        this.windowDAO = windowDAO;
        this.traceTopicsDAO = traceTopicsDAO;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Throws not found for a project of another workspace. */
    public ProjectTopics get(@NonNull String workspaceId, String workspaceName, @NonNull UUID projectId) {
        projectService.get(projectId, workspaceId);
        long start = System.nanoTime();
        ProjectTopics.Status status = null;
        try {
            var body = read(workspaceId, projectId);
            status = body.status();
            if (body.other() != null && body.other().stale() > 0) {
                metrics.apiStale(TopicsWorkspaceNames.builder(workspaceId,
                        Objects.requireNonNullElse(workspaceName, workspaceId)), body.other().stale());
            }
            return body;
        } finally {
            metrics.apiRequest(status, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private ProjectTopics read(String workspaceId, UUID projectId) {
        var today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        var snapshot = template.inTransaction(READ_ONLY, handle -> {
            if (!handle.attach(TopicWorkspaceDAO.class).isEligible(workspaceId)) {
                return Optional.<ProjectSnapshot>empty();
            }
            var topicDAO = handle.attach(TopicDAO.class);
            var live = topicDAO.findLive(workspaceId, projectId);
            var state = topicDAO.findState(workspaceId, projectId).orElse(null);
            if (live.isEmpty()) {
                return Optional.of(ProjectSnapshot.builder().live(live).state(state).savedRunIds(List.of())
                        .runCounts(List.of()).build());
            }
            var runDAO = handle.attach(TopicRunDAO.class);
            return Optional.of(ProjectSnapshot.builder()
                    .live(live)
                    .state(state)
                    .savedRunIds(runDAO.findSavedRunIds(workspaceId, projectId))
                    .runCounts(runDAO.findLastTwoRunCounts(workspaceId, projectId))
                    .build());
        });
        if (snapshot.isEmpty()) {
            return TopicsCounts.window(today).status(ProjectTopics.Status.DISABLED).build();
        }

        var live = snapshot.get().live();
        var state = snapshot.get().state();
        var from = startOf(TopicsCounts.windowFrom(today));
        var to = startOf(today.plusDays(1));
        if (live.isEmpty()) {
            long vectors = blockOrUnavailable(workspaceId, projectId, windowDAO
                    .countByProject(workspaceId, projectId, from, to, MAX_EXECUTION_SECONDS)
                    .next()
                    .map(TopicWindowDAO.ProjectCount::traces)
                    .defaultIfEmpty(0L));
            return TopicsCounts.window(today).status(ProjectTopics.Status.COLLECTING)
                    .lastRunAt(TopicsCounts.lastRunAt(state))
                    .vectorsInWindow(vectors)
                    .minVectors((long) config.getNightly().getMinVectors())
                    .build();
        }
        var rows = blockOrUnavailable(workspaceId, projectId, traceTopicsDAO
                .countByTopicAndDay(workspaceId, projectId, from, to, snapshot.get().savedRunIds(),
                        MAX_EXECUTION_SECONDS)
                .collectList());
        return TopicsCounts.ready(today, live, state, rows, snapshot.get().runCounts());
    }

    /** Blocks on a ClickHouse read; a query limit or the client timeout becomes a 503 with Retry-After. */
    private <T> T blockOrUnavailable(String workspaceId, UUID projectId, Mono<T> read) {
        try {
            return read.timeout(CLIENT_TIMEOUT).block();
        } catch (RuntimeException exception) {
            if (!isLimit(exception)) {
                throw exception;
            }
            log.warn("Topics read hit a ClickHouse limit, workspace_id '{}', project_id '{}'", workspaceId, projectId,
                    exception);
            throw new ServiceUnavailableException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .entity(new ErrorMessage(List.of("Topics are busy; retry shortly")))
                    .build());
        }
    }

    private static boolean isLimit(Throwable error) {
        return Throwables.getCausalChain(error).stream().anyMatch(cause -> cause instanceof TimeoutException
                || (cause instanceof ServerException server && ClickHouseQueryLimits.CODES.contains(server.getCode())));
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** The MySQL reads of one request, taken in one transaction. */
    @Builder(toBuilder = true)
    private record ProjectSnapshot(List<Topic> live, TopicProjectState state, List<UUID> savedRunIds,
            List<TopicRunCount> runCounts) {
    }
}
